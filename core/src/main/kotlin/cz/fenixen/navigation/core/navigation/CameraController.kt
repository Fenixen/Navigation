package cz.fenixen.navigation.core.navigation

import cz.fenixen.navigation.core.geo.GeoBounds
import cz.fenixen.navigation.core.geo.GeoPoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class FollowMode {
    /** Camera follows the car. */
    FOLLOW,
    /** User panned the map – camera stays where the user left it until "recenter". */
    FREE,
    /** Whole (remaining) route visible. */
    OVERVIEW,
}

enum class MapOrientation { HEADING_UP, NORTH_UP }

/**
 * Zoom policy – the whole reason this class exists.
 *
 * Google Maps / Android Auto auto-zoom constantly and reset your zoom after a few seconds.
 * Here a zoom chosen by the user is a **lock**: it survives following, recentering, rerouting,
 * new trips and app restarts (persisted through [CameraController.onZoomLockChanged]).
 * Only an explicit "AUTO" action unlocks it.
 */
sealed interface ZoomPolicy {
    data object Auto : ZoomPolicy
    data class Locked(val zoom: Double) : ZoomPolicy
}

data class CameraState(
    val followMode: FollowMode = FollowMode.FOLLOW,
    val orientation: MapOrientation = MapOrientation.HEADING_UP,
    val zoomPolicy: ZoomPolicy = ZoomPolicy.Auto,
    /** Zoom the camera is currently using (locked value or the computed auto-zoom). */
    val effectiveZoom: Double = 16.5,
)

/**
 * Where the car sits on screen. The map camera centres on the middle of its padded rectangle,
 * so to put the car at height y we need: (padTop + height − padBottom) / 2 = y.
 */
object CameraAnchor {
    /** Car at 70 % of the screen height from the top = lower third, lots of road visible ahead. */
    const val HEADING_UP_SCREEN_Y = 0.70

    /**
     * Top padding (px) for the camera. [obstructedTop]/[obstructedBottom] are heights covered by
     * banners/docks; the car stays inside the free area between them.
     */
    fun paddingTop(height: Int, obstructedTop: Int, obstructedBottom: Int, headingUp: Boolean, minGapPx: Int = 0): Double {
        if (height <= 0) return obstructedTop.toDouble()
        val freeTop = obstructedTop.toDouble()
        val freeBottom = (height - obstructedBottom).toDouble()
        if (freeBottom <= freeTop) return freeTop
        if (!headingUp) return freeTop // sever nahoře → auto uprostřed volné plochy
        val y = (height * HEADING_UP_SCREEN_Y).coerceIn((freeTop + freeBottom) / 2, freeBottom - minGapPx)
        return (2 * y - height + obstructedBottom).coerceAtLeast(freeTop)
    }
}

sealed interface CameraCommand {
    /** Move/animate the camera to follow the car. */
    data class Follow(
        val center: GeoPoint,
        val zoom: Double,
        val bearing: Double,
        val tilt: Double,
        val animationMs: Long,
    ) : CameraCommand

    /** Show these bounds (route overview / route preview). */
    data class Fit(val bounds: GeoBounds, val animationMs: Long = 800) : CameraCommand

    /** Only change zoom, keep center (zoom buttons while in FREE mode). */
    data class ZoomTo(val zoom: Double, val animationMs: Long = 250) : CameraCommand
}

data class CameraConfig(
    val minZoom: Double = 3.0,
    /** MapLibre can go to 22+, but beyond ~20.5 vector tiles are just blurry overzoom. */
    val maxZoom: Double = 20.5,
    val zoomStep: Double = 1.0,
    val navigationTilt: Double = 50.0,
    val followAnimationMs: Long = 900,
    /** Exponential smoothing of auto-zoom (0..1, higher = faster reaction). */
    val autoZoomSmoothing: Double = 0.25,
    /** speed km/h → zoom. Linear interpolation between knots. */
    val autoZoomKnots: List<Pair<Double, Double>> = listOf(
        0.0 to 17.5,
        30.0 to 17.25,
        50.0 to 16.75,
        70.0 to 16.25,
        90.0 to 15.75,
        110.0 to 15.25,
        130.0 to 14.75,
    ),
    /** Auto-zoom gets closer when a maneuver is near – but never in locked mode. */
    val maneuverZoomBoost: Double = 0.75,
    val maneuverZoomDistanceM: Double = 250.0,
)

/**
 * Platform-independent camera brain. The map adapter (MapLibre on phone / car surface)
 * just executes [CameraCommand]s and reports user gestures back.
 *
 * Not thread-safe – call from the main thread.
 */
class CameraController(
    initialZoomLock: Double? = null,
    initialOrientation: MapOrientation = MapOrientation.HEADING_UP,
    private val config: CameraConfig = CameraConfig(),
    /** Persist the lock (DataStore on Android). null = auto zoom. */
    private val onZoomLockChanged: (Double?) -> Unit = {},
) {
    private val _state = MutableStateFlow(
        CameraState(
            orientation = initialOrientation,
            zoomPolicy = initialZoomLock?.let { ZoomPolicy.Locked(clampZoom(it)) } ?: ZoomPolicy.Auto,
            effectiveZoom = initialZoomLock?.let(::clampZoom) ?: config.autoZoomKnots.first().second,
        ),
    )
    val state: StateFlow<CameraState> = _state.asStateFlow()

    private var smoothedAutoZoom: Double? = null
    private var lastPosition: GeoPoint? = null
    private var lastBearing: Double = 0.0
    private var navigating = false

    val isZoomLocked: Boolean get() = _state.value.zoomPolicy is ZoomPolicy.Locked

    /** Restores persisted preferences (no persistence callback – the value came from storage). */
    fun restore(zoomLock: Double?, orientation: MapOrientation) {
        _state.update {
            it.copy(
                zoomPolicy = zoomLock?.let { z -> ZoomPolicy.Locked(clampZoom(z)) } ?: ZoomPolicy.Auto,
                effectiveZoom = zoomLock?.let(::clampZoom) ?: it.effectiveZoom,
                orientation = orientation,
            )
        }
    }

    /**
     * Called on every (snapped) position update. Returns a command only in FOLLOW mode.
     * [distanceToManeuver] is null when not navigating.
     */
    fun onPosition(
        position: GeoPoint,
        bearing: Double,
        speedMps: Double?,
        distanceToManeuver: Double?,
        navigating: Boolean,
    ): CameraCommand? {
        lastPosition = position
        lastBearing = bearing
        this.navigating = navigating
        val s = _state.value
        val zoom = when (val policy = s.zoomPolicy) {
            is ZoomPolicy.Locked -> policy.zoom
            ZoomPolicy.Auto -> autoZoom(speedMps, distanceToManeuver)
        }
        _state.update { it.copy(effectiveZoom = zoom) }
        if (s.followMode != FollowMode.FOLLOW) return null
        return followCommand(position, bearing, zoom)
    }

    /**
     * User finished a pinch/zoom gesture (or rotary/zoom button) at [zoom].
     * In FOLLOW mode that's the driver choosing their zoom → lock it. In FREE mode
     * the user is just exploring the map, so we leave the driving zoom untouched.
     */
    fun onUserZoom(zoom: Double): CameraCommand? {
        val clamped = clampZoom(zoom)
        return when (_state.value.followMode) {
            FollowMode.FOLLOW -> {
                lock(clamped)
                lastPosition?.let { followCommand(it, lastBearing, clamped) } ?: CameraCommand.ZoomTo(clamped)
            }
            FollowMode.FREE, FollowMode.OVERVIEW -> null
        }
    }

    /** +/- buttons. [currentMapZoom] is the zoom the map shows right now (needed in FREE mode). */
    fun zoomBy(steps: Int, currentMapZoom: Double? = null): CameraCommand? {
        val s = _state.value
        val base = when (s.followMode) {
            FollowMode.FOLLOW -> s.effectiveZoom
            else -> currentMapZoom ?: s.effectiveZoom
        }
        val target = clampZoom(base + steps * config.zoomStep)
        return when (s.followMode) {
            FollowMode.FOLLOW -> {
                lock(target)
                lastPosition?.let { followCommand(it, lastBearing, target) } ?: CameraCommand.ZoomTo(target)
            }
            else -> CameraCommand.ZoomTo(target)
        }
    }

    /** Explicitly lock [zoom] (e.g. "lock current zoom" button) – works in any follow mode. */
    fun lockAt(zoom: Double): CameraCommand? {
        lock(zoom)
        val pos = lastPosition ?: return null
        return if (_state.value.followMode == FollowMode.FOLLOW) followCommand(pos, lastBearing, clampZoom(zoom)) else null
    }

    /** Jump straight to the maximum zoom and lock it ("max zoom napořád"). */
    fun lockMaxZoom(): CameraCommand {
        lock(config.maxZoom)
        val pos = lastPosition
        return if (_state.value.followMode == FollowMode.FOLLOW && pos != null) {
            followCommand(pos, lastBearing, config.maxZoom)
        } else {
            CameraCommand.ZoomTo(config.maxZoom)
        }
    }

    /** Explicitly return to automatic, speed-based zoom. */
    fun enableAutoZoom(): CameraCommand? {
        smoothedAutoZoom = null
        _state.update { it.copy(zoomPolicy = ZoomPolicy.Auto) }
        onZoomLockChanged(null)
        return lastPosition?.let { pos ->
            if (_state.value.followMode == FollowMode.FOLLOW) {
                followCommand(pos, lastBearing, autoZoom(null, null))
            } else {
                null
            }
        }
    }

    fun onUserPan() {
        _state.update { it.copy(followMode = FollowMode.FREE) }
    }

    /** Back to following. Locked zoom is kept – that's the point. */
    fun recenter(): CameraCommand? {
        _state.update { it.copy(followMode = FollowMode.FOLLOW) }
        val pos = lastPosition ?: return null
        return followCommand(pos, lastBearing, _state.value.effectiveZoom)
    }

    fun showOverview(points: List<GeoPoint>): CameraCommand? {
        if (points.isEmpty()) return null
        _state.update { it.copy(followMode = FollowMode.OVERVIEW) }
        return CameraCommand.Fit(GeoBounds.of(points))
    }

    fun toggleOrientation(): MapOrientation {
        val next = when (_state.value.orientation) {
            MapOrientation.HEADING_UP -> MapOrientation.NORTH_UP
            MapOrientation.NORTH_UP -> MapOrientation.HEADING_UP
        }
        _state.update { it.copy(orientation = next) }
        return next
    }

    private fun lock(zoom: Double) {
        val clamped = clampZoom(zoom)
        _state.update { it.copy(zoomPolicy = ZoomPolicy.Locked(clamped), effectiveZoom = clamped) }
        onZoomLockChanged(clamped)
    }

    private fun followCommand(position: GeoPoint, bearing: Double, zoom: Double): CameraCommand.Follow {
        val headingUp = _state.value.orientation == MapOrientation.HEADING_UP
        return CameraCommand.Follow(
            center = position,
            zoom = zoom,
            bearing = if (headingUp) bearing else 0.0,
            tilt = if (headingUp && navigating) config.navigationTilt else 0.0,
            animationMs = config.followAnimationMs,
        )
    }

    internal fun autoZoom(speedMps: Double?, distanceToManeuver: Double?): Double {
        val kmh = (speedMps ?: 0.0) * 3.6
        var target = interpolate(config.autoZoomKnots, kmh)
        if (distanceToManeuver != null && distanceToManeuver < config.maneuverZoomDistanceM && kmh < 80) {
            val closeness = 1.0 - distanceToManeuver / config.maneuverZoomDistanceM
            target += config.maneuverZoomBoost * closeness
        }
        val prev = smoothedAutoZoom
        val smoothed = if (prev == null) target else prev + (target - prev) * config.autoZoomSmoothing
        smoothedAutoZoom = smoothed
        return clampZoom(smoothed)
    }

    private fun clampZoom(z: Double) = z.coerceIn(config.minZoom, config.maxZoom)

    private fun interpolate(knots: List<Pair<Double, Double>>, x: Double): Double {
        if (x <= knots.first().first) return knots.first().second
        if (x >= knots.last().first) return knots.last().second
        for (i in 1 until knots.size) {
            val (x1, y1) = knots[i]
            if (x <= x1) {
                val (x0, y0) = knots[i - 1]
                return y0 + (y1 - y0) * (x - x0) / (x1 - x0)
            }
        }
        return knots.last().second
    }
}
