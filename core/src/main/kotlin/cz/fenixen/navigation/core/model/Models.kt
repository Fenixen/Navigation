package cz.fenixen.navigation.core.model

import cz.fenixen.navigation.core.geo.GeoPoint
import cz.fenixen.navigation.core.geo.Polyline

/** A searchable/selectable place (search result, favourite, destination). */
data class Place(
    val name: String,
    val point: GeoPoint,
    val address: String? = null,
    val category: String? = null,
)

/** One GPS fix as delivered by the platform (fused location on Android). */
data class LocationFix(
    val point: GeoPoint,
    val timeMillis: Long,
    /** Horizontal accuracy radius (68 %), metres. */
    val accuracyM: Double? = null,
    /** Direction of travel in degrees, null when unknown (e.g. standing still). */
    val bearingDeg: Double? = null,
    /** Ground speed m/s, null when unknown. */
    val speedMps: Double? = null,
)

enum class ManeuverType {
    DEPART, DEPART_LEFT, DEPART_RIGHT,
    ARRIVE, ARRIVE_LEFT, ARRIVE_RIGHT,
    CONTINUE, NAME_CHANGE,
    SLIGHT_LEFT, LEFT, SHARP_LEFT,
    SLIGHT_RIGHT, RIGHT, SHARP_RIGHT,
    UTURN_LEFT, UTURN_RIGHT,
    RAMP_STRAIGHT, RAMP_LEFT, RAMP_RIGHT,
    EXIT_LEFT, EXIT_RIGHT,
    KEEP_STRAIGHT, KEEP_LEFT, KEEP_RIGHT,
    MERGE, MERGE_LEFT, MERGE_RIGHT,
    ROUNDABOUT_ENTER, ROUNDABOUT_EXIT,
    FERRY_ENTER, FERRY_EXIT,
    UNKNOWN;

    val isArrival: Boolean get() = this == ARRIVE || this == ARRIVE_LEFT || this == ARRIVE_RIGHT
}

/** Highway signage attached to a maneuver ("Exit 230 → Olomouc, Ostrava"). */
data class ManeuverSign(
    val exitNumber: String? = null,
    val exitName: String? = null,
    val branch: List<String> = emptyList(),
    val toward: List<String> = emptyList(),
)

data class Maneuver(
    val type: ManeuverType,
    /** Text for the banner ("Odbočte vpravo na Husova."). */
    val instruction: String,
    /** Short spoken form used with a distance prefix ("Za 300 metrů odbočte vpravo."). */
    val verbalAlert: String? = null,
    /** Spoken right before the maneuver. */
    val verbalPre: String? = null,
    val streetNames: List<String> = emptyList(),
    val sign: ManeuverSign? = null,
    val roundaboutExitCount: Int? = null,
    /** Index into the route geometry where this maneuver happens. */
    val beginShapeIndex: Int,
    /** Distance from route start to this maneuver, metres (computed from geometry). */
    val distanceFromStart: Double,
    /** Length of the stretch that follows this maneuver (until the next one), metres. */
    val lengthMeters: Double,
    /** Expected travel time of the stretch that follows, seconds. */
    val durationSeconds: Double,
    val isToll: Boolean = false,
    val isHighway: Boolean = false,
) {
    /** Primary road name for UI ("Husova" / "D1"). */
    val primaryName: String? get() = streetNames.firstOrNull()
}

/** Speed limit valid on [startDistance, endDistance) along the route. null kph = unknown. */
data class SpeedLimitSpan(val startDistance: Double, val endDistance: Double, val kph: Int?)

data class Route(
    val id: String,
    val geometry: Polyline,
    val maneuvers: List<Maneuver>,
    val distanceMeters: Double,
    val durationSeconds: Double,
    /** Main roads used ("D1, D46"), for comparing alternatives. */
    val summary: String,
    val hasToll: Boolean = false,
    val hasHighway: Boolean = false,
    val hasFerry: Boolean = false,
    val speedLimits: List<SpeedLimitSpan> = emptyList(),
    /** Live traffic for (the remaining part of) this route – null without a traffic provider. */
    val traffic: RouteTraffic? = null,
) {
    init {
        require(maneuvers.isNotEmpty()) { "Route must contain at least one maneuver" }
    }

    val destination: GeoPoint get() = geometry.points.last()

    /** Binary search in the (sorted, non-overlapping) speed limit spans. */
    fun speedLimitAt(distanceAlong: Double): Int? {
        var lo = 0
        var hi = speedLimits.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val span = speedLimits[mid]
            when {
                distanceAlong < span.startDistance -> hi = mid - 1
                distanceAlong >= span.endDistance -> lo = mid + 1
                else -> return span.kph
            }
        }
        return null
    }
}

data class RouteOptions(
    val avoidTolls: Boolean = false,
    val avoidHighways: Boolean = false,
    val avoidFerries: Boolean = false,
    /** How many alternative routes to request in addition to the best one. */
    val alternatives: Int = 2,
)

data class RouteRequest(
    val origin: GeoPoint,
    val destination: GeoPoint,
    /** Current heading – lets the router prefer continuing forward (important when rerouting). */
    val originBearing: Double? = null,
    val options: RouteOptions = RouteOptions(),
)

enum class TrafficLevel { FREE, SLOW, QUEUE, JAM, CLOSED }

/** Traffic condition on [startDistance, endDistance) along the route (metres from route start). */
data class TrafficSpan(
    val startDistance: Double,
    val endDistance: Double,
    val level: TrafficLevel,
    val delaySeconds: Double = 0.0,
    val speedKmh: Int? = null,
)

data class RouteTraffic(
    /** Along-route distance from which the numbers below are valid (current position at fetch time). */
    val fromDistance: Double,
    /** Travel time from [fromDistance] to the destination *with* current traffic, seconds. */
    val travelTimeSeconds: Double,
    /** Extra time caused by traffic, seconds. */
    val delaySeconds: Double,
    val spans: List<TrafficSpan>,
    val fetchedAtMillis: Long,
)
