package cz.fenixen.navigation.core.navigation

import cz.fenixen.navigation.core.model.RouteTraffic
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EtaEstimatorTest {
    /** Router predicts 3 600 s; we drive [pace]× its times. Returns the corrected remaining time halfway. */
    private fun drive(pace: Double, eta: EtaEstimator = EtaEstimator(), waitAtStartS: Int = 0): Double {
        var now = 0L
        var remaining = 3_600.0
        repeat(waitAtStartS) { eta.update(remaining, 0.0, now); now += 1_000 }
        var corrected = remaining
        while (remaining > 1_800) {
            corrected = eta.update(remaining, 20.0, now)
            remaining -= 1.0 / pace // za 1 s skutečného času ujedeme 1/pace sekund „routerového“ času
            now += 1_000
        }
        return corrected
    }

    @Test
    fun `learns that we drive faster than the router thinks`() {
        val corrected = drive(pace = 0.8)
        assertEquals(1_800 * 0.8, corrected, 1_800 * 0.05)
    }

    @Test
    fun `learns slower pace too, but clamps extreme values`() {
        assertEquals(1_800 * 1.2, drive(pace = 1.2), 1_800 * 0.05)
        assertTrue(drive(pace = 3.0) <= 1_800 * 1.6 + 1)
    }

    @Test
    fun `waiting before departure does not inflate the ETA`() {
        val corrected = drive(pace = 1.0, waitAtStartS = 600)
        assertEquals(1_800.0, corrected, 1_800 * 0.03)
    }

    @Test
    fun `traffic factor applies immediately`() {
        val eta = EtaEstimator()
        eta.onTraffic(RouteTraffic(0.0, travelTimeSeconds = 3_000.0, delaySeconds = 0.0, spans = emptyList(), fetchedAtMillis = 0), rawRemainingAtFetch = 3_600.0)
        assertEquals(3_000.0, eta.update(3_600.0, 0.0, 0), 1.0)
    }
}
