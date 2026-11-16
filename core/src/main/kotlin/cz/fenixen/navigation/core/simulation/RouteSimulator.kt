package cz.fenixen.navigation.core.simulation

import cz.fenixen.navigation.core.model.LocationFix
import cz.fenixen.navigation.core.model.Route
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * "Virtual drive" along a route – indispensable for testing on the Android Auto
 * Desktop Head Unit without actually driving (like a sim session before the race weekend).
 *
 * Speed of each stretch = its length / router's expected duration, so the simulated car
 * drives 130 on the motorway and 30 in residential streets.
 */
class RouteSimulator(
    private val route: Route,
    private val speedMultiplier: Double = 1.0,
) {
    private val sectionEndTimes: DoubleArray
    private val sectionSpeeds: DoubleArray

    init {
        val ms = route.maneuvers
        sectionEndTimes = DoubleArray(ms.size)
        sectionSpeeds = DoubleArray(ms.size)
        var t = 0.0
        for (i in ms.indices) {
            val m = ms[i]
            val speed = if (m.durationSeconds > 0) (m.lengthMeters / m.durationSeconds) else 0.0
            sectionSpeeds[i] = speed.coerceIn(MIN_SPEED_MPS, MAX_SPEED_MPS)
            t += if (m.lengthMeters > 0) m.lengthMeters / sectionSpeeds[i] else 0.0
            sectionEndTimes[i] = t
        }
    }

    val totalSeconds: Double get() = sectionEndTimes.lastOrNull() ?: 0.0

    /** Fix after [elapsedSeconds] of simulated driving, null once past the destination. */
    fun fixAt(elapsedSeconds: Double, timeMillis: Long): LocationFix? {
        if (elapsedSeconds > totalSeconds) return null
        val ms = route.maneuvers
        var i = 0
        while (i < ms.size - 1 && elapsedSeconds > sectionEndTimes[i]) i++
        val sectionStart = if (i == 0) 0.0 else sectionEndTimes[i - 1]
        val distance = ms[i].distanceFromStart + (elapsedSeconds - sectionStart) * sectionSpeeds[i]
        val geometry = route.geometry
        return LocationFix(
            point = geometry.pointAt(distance),
            timeMillis = timeMillis,
            accuracyM = 4.0,
            bearingDeg = geometry.bearingAt(distance),
            speedMps = sectionSpeeds[i],
        )
    }

    fun fixes(tickMillis: Long = 1_000, clock: () -> Long = System::currentTimeMillis): Flow<LocationFix> = flow {
        var elapsed = 0.0
        while (true) {
            val fix = fixAt(elapsed, clock()) ?: break
            emit(fix)
            delay(tickMillis)
            elapsed += tickMillis / 1000.0 * speedMultiplier
        }
        // Poslední fix přesně v cíli, aby navigace spolehlivě ohlásila příjezd.
        fixAt(totalSeconds, clock())?.let { emit(it) }
    }

    private companion object {
        const val MIN_SPEED_MPS = 4.0
        const val MAX_SPEED_MPS = 37.0
    }
}
