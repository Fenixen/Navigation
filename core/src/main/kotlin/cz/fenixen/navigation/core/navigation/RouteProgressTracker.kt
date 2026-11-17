package cz.fenixen.navigation.core.navigation

import cz.fenixen.navigation.core.geo.Geo
import cz.fenixen.navigation.core.geo.GeoPoint
import cz.fenixen.navigation.core.geo.Projection
import cz.fenixen.navigation.core.model.LocationFix
import cz.fenixen.navigation.core.model.Maneuver
import cz.fenixen.navigation.core.model.Route
import kotlin.math.abs

/** Where we are on the active route. All distances in metres, durations in seconds. */
data class RouteProgress(
    val distanceAlong: Double,
    val distanceRemaining: Double,
    val durationRemaining: Double,
    /** Index of the next maneuver to perform (into [Route.maneuvers]). */
    val upcomingManeuverIndex: Int,
    val distanceToUpcoming: Double,
    /** Expected time until the upcoming maneuver (instrument cluster / HUD), seconds. */
    val durationToUpcoming: Double,
    /** Position snapped onto the route – drawn as the car puck while on route. */
    val snappedPoint: GeoPoint,
    val snappedBearing: Double,
    val distanceFromRoute: Double,
    val speedLimitKph: Int?,
)

enum class TrackingStatus { ON_ROUTE, OFF_ROUTE, ARRIVED }

data class TrackingResult(val progress: RouteProgress, val status: TrackingStatus)

data class TrackerConfig(
    /** Minimum distance from the route that counts as "off route". */
    val offRouteThresholdM: Double = 35.0,
    /** Upper bound for the accuracy-scaled threshold (bad GPS shouldn't hide real deviations forever). */
    val offRouteThresholdMaxM: Double = 80.0,
    /** Consecutive bad fixes required – one GPS glitch must not trigger a reroute. */
    val offRouteStrikes: Int = 3,
    /** Way off (e.g. took a parallel road) → react faster. */
    val farOffRouteM: Double = 150.0,
    val lookBehindM: Double = 30.0,
    val lookAheadBaseM: Double = 400.0,
    /** Penalty (in metres) for a 180° heading mismatch when choosing the matching segment. */
    val headingWeightM: Double = 30.0,
    /** Heading difference that means "driving the wrong way". */
    val wrongWayAngleDeg: Double = 135.0,
    val wrongWayStrikes: Int = 5,
    val arrivalDistanceM: Double = 30.0,
    /** Speeds below this are too noisy for heading-based logic. */
    val minSpeedForHeadingMps: Double = 3.0,
    /** Below this speed the car is considered standing (position frozen against GPS drift). */
    val stationarySpeedMps: Double = 1.0,
    /** Backward correction needs the projection to be at least this far behind… */
    val backCorrectionMinM: Double = 8.0,
    /** …for this many consecutive fixes. */
    val backCorrectionStrikes: Int = 3,
)

/**
 * Snaps GPS fixes onto the route and derives progress, ETA inputs and the off-route signal.
 *
 * Key ideas (same as in production nav SDKs):
 *  - **Windowed search**: only segments around the last known position are considered.
 *    That's O(window) instead of O(route), and it prevents snapping onto a part of the
 *    route that passes nearby later (overpasses, loops, return legs).
 *  - **Heading-aware scoring**: distance + penalty for heading mismatch, so on a dual
 *    carriageway we match the lane going our direction.
 *  - **Hysteresis**: off-route needs several consecutive bad fixes.
 *  - **Stable progress**: frozen at standstill, forward immediately, backward only when
 *    several fixes agree (see [advanceAlong]).
 */
class RouteProgressTracker(
    route: Route,
    private val config: TrackerConfig = TrackerConfig(),
) {
    var route: Route = route
        private set
    private val geometry = route.geometry
    private var distanceAlong = 0.0
    private var offRouteCount = 0
    private var wrongWayCount = 0
    private var behindCount = 0
    private var arrived = false

    fun update(fix: LocationFix): TrackingResult {
        val speed = fix.speedMps ?: 0.0
        val heading = fix.bearingDeg?.takeIf { speed >= config.minSpeedForHeadingMps }

        val lookAhead = config.lookAheadBaseM + speed * 10.0
        val fromSeg = geometry.segmentIndexAt(distanceAlong - config.lookBehindM)
        val toSeg = geometry.segmentIndexAt(distanceAlong + lookAhead)
        val scorer = scorerFor(heading)

        var best = geometry.project(fix.point, fromSeg, toSeg, scorer)
        val threshold = threshold(fix)

        // Mimo okno? (tunel, výpadek GPS, skok polohy) → zkusíme celý zbytek trasy dopředu.
        if (best.distanceFromLine > threshold && toSeg < geometry.segmentCount - 1) {
            val global = geometry.project(fix.point, fromSeg, geometry.segmentCount - 1, scorer)
            if (global.distanceFromLine <= threshold) best = global
        }

        val onRouteGeometrically = best.distanceFromLine <= threshold
        offRouteCount = if (onRouteGeometrically) 0 else offRouteCount + 1

        val wrongWay = heading != null &&
            abs(Geo.bearingDelta(best.segmentBearing, heading)) > config.wrongWayAngleDeg
        wrongWayCount = if (wrongWay) wrongWayCount + 1 else 0

        if (onRouteGeometrically) advanceAlong(best.distanceAlong, fix)

        val progress = buildProgress(best, fix.point, onRouteGeometrically)
        val isOffRoute = offRouteCount >= config.offRouteStrikes ||
            (offRouteCount >= 2 && best.distanceFromLine > config.farOffRouteM) ||
            wrongWayCount >= config.wrongWayStrikes

        if (!arrived && onRouteGeometrically && progress.distanceRemaining <= config.arrivalDistanceM) {
            arrived = true
        }
        val status = when {
            arrived -> TrackingStatus.ARRIVED
            isOffRoute -> TrackingStatus.OFF_ROUTE
            else -> TrackingStatus.ON_ROUTE
        }
        return TrackingResult(progress, status)
    }

    /**
     * Moves the along-route position. Rules (they're what keeps the arrow exactly where the car is
     * at a red light):
     *  - **standing still → freeze**: GPS wanders several metres at standstill; following it would
     *    creep the arrow into the junction,
     *  - forward movement is taken immediately,
     *  - backward correction only when several fixes in a row say we're clearly behind
     *    (the arrow overshot while braking).
     */
    private fun advanceAlong(projected: Double, fix: LocationFix) {
        val speed = fix.speedMps
        val stationary = speed != null && speed < config.stationarySpeedMps
        val delta = projected - distanceAlong
        when {
            stationary -> behindCount = 0
            delta >= 0 -> {
                distanceAlong = projected
                behindCount = 0
            }
            -delta > maxOf(config.backCorrectionMinM, fix.accuracyM ?: 0.0) && -delta <= config.lookBehindM -> {
                behindCount++
                if (behindCount >= config.backCorrectionStrikes) {
                    distanceAlong = projected
                    behindCount = 0
                }
            }
            else -> behindCount = 0
        }
    }

    /** Swap in enriched data (e.g. speed limits) for the same geometry without losing progress. */
    fun replaceRouteData(enriched: Route) {
        require(enriched.geometry === geometry) { "Geometry must stay the same – create a new tracker instead" }
        route = enriched
    }

    private fun threshold(fix: LocationFix): Double {
        val acc = fix.accuracyM ?: 0.0
        return maxOf(config.offRouteThresholdM, acc * 1.5).coerceAtMost(config.offRouteThresholdMaxM)
    }

    private fun scorerFor(heading: Double?): ((Projection) -> Double)? {
        if (heading == null) return null
        return { proj ->
            val delta = abs(Geo.bearingDelta(proj.segmentBearing, heading))
            proj.distanceFromLine + config.headingWeightM * (delta / 180.0)
        }
    }

    private fun buildProgress(best: Projection, raw: GeoPoint, onRoute: Boolean): RouteProgress {
        val maneuvers = route.maneuvers
        val upcoming = upcomingIndex(maneuvers, distanceAlong)
        val upcomingManeuver = maneuvers[upcoming]
        val distanceToUpcoming = (upcomingManeuver.distanceFromStart - distanceAlong).coerceAtLeast(0.0)

        // Zbývající čas: poměrná část aktuálního úseku + součet časů všech dalších úseků.
        // Časy z routeru zohledňují typ silnice, takže je to přesnější než vzdálenost / rychlost.
        val current = maneuvers[(upcoming - 1).coerceAtLeast(0)]
        val currentFraction = if (current.lengthMeters > 0) {
            (distanceToUpcoming / current.lengthMeters).coerceIn(0.0, 1.0)
        } else {
            0.0
        }
        val timeToUpcoming = if (upcoming > 0) current.durationSeconds * currentFraction else 0.0
        var remainingTime = timeToUpcoming
        for (i in upcoming until maneuvers.size) remainingTime += maneuvers[i].durationSeconds

        // Na trase kreslíme polohu přichycenou k trase (žádné „plavání“ vedle silnice),
        // mimo trasu poctivě skutečnou GPS polohu.
        val snapped = if (onRoute) geometry.pointAt(distanceAlong) else raw
        return RouteProgress(
            distanceAlong = distanceAlong,
            distanceRemaining = (geometry.length - distanceAlong).coerceAtLeast(0.0),
            durationRemaining = remainingTime,
            upcomingManeuverIndex = upcoming,
            distanceToUpcoming = distanceToUpcoming,
            durationToUpcoming = timeToUpcoming,
            snappedPoint = snapped,
            snappedBearing = geometry.bearingAt(distanceAlong),
            distanceFromRoute = best.distanceFromLine,
            speedLimitKph = route.speedLimitAt(distanceAlong),
        )
    }

    companion object {
        /** Tolerance so that standing exactly on a maneuver point counts as "passed". */
        private const val PASSED_EPSILON_M = 1.0

        /** First maneuver strictly ahead of [distanceAlong]; the final arrival if none. */
        fun upcomingIndex(maneuvers: List<Maneuver>, distanceAlong: Double): Int {
            for (i in 1 until maneuvers.size) {
                if (maneuvers[i].distanceFromStart > distanceAlong + PASSED_EPSILON_M) return i
            }
            return maneuvers.size - 1
        }
    }
}
