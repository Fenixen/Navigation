package cz.fenixen.navigation.core.navigation

import cz.fenixen.navigation.core.Fixtures
import cz.fenixen.navigation.core.geo.Geo
import cz.fenixen.navigation.core.model.LocationFix
import cz.fenixen.navigation.core.simulation.RouteSimulator
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RouteProgressTrackerTest {
    private val route = Fixtures.route("valhalla_route_highway.json")

    private fun drive(every: Double = 2.0): List<LocationFix> {
        val sim = RouteSimulator(route)
        return generateSequence(0.0) { it + every }
            .map { sim.fixAt(it, (it * 1000).toLong()) }
            .takeWhile { it != null }
            .filterNotNull()
            .toList() + listOfNotNull(sim.fixAt(sim.totalSeconds, 0))
    }

    @Test
    fun `clean drive stays on route, progresses monotonically and arrives`() {
        val tracker = RouteProgressTracker(route)
        var lastAlong = -1.0
        var lastUpcoming = 0
        var lastRemainingTime = Double.MAX_VALUE
        val statuses = mutableListOf<TrackingStatus>()
        for (fix in drive()) {
            val r = tracker.update(fix)
            statuses += r.status
            assertTrue(r.progress.distanceAlong >= lastAlong, "progress went backwards")
            assertTrue(r.progress.upcomingManeuverIndex >= lastUpcoming, "maneuver index went backwards")
            assertTrue(r.progress.durationRemaining <= lastRemainingTime + 1e-6, "ETA must not grow on a clean drive")
            lastAlong = r.progress.distanceAlong
            lastUpcoming = r.progress.upcomingManeuverIndex
            lastRemainingTime = r.progress.durationRemaining
        }
        assertTrue(TrackingStatus.OFF_ROUTE !in statuses, "never off route on a clean drive")
        assertEquals(TrackingStatus.ARRIVED, statuses.last())
        assertEquals(route.maneuvers.lastIndex, lastUpcoming)
    }

    @Test
    fun `remaining duration at start matches router estimate`() {
        val tracker = RouteProgressTracker(route)
        val first = tracker.update(drive().first())
        assertEquals(route.durationSeconds, first.progress.durationRemaining, route.durationSeconds * 0.02)
        assertEquals(route.geometry.length, first.progress.distanceRemaining, 5.0)
    }

    @Test
    fun `gps noise does not cause off-route or backwards progress`() {
        val tracker = RouteProgressTracker(route)
        val rnd = Random(5)
        var lastAlong = 0.0
        for (fix in drive()) {
            val noisy = fix.copy(
                point = Geo.destination(fix.point, rnd.nextDouble(0.0, 360.0), rnd.nextDouble(0.0, 12.0)),
                accuracyM = 10.0,
            )
            val r = tracker.update(noisy)
            assertTrue(r.status != TrackingStatus.OFF_ROUTE, "noise caused off-route at ${r.progress.distanceAlong}")
            // Malé zpětné korekce (přejetí při brzdění) jsou povolené, skoky ne.
            assertTrue(r.progress.distanceAlong >= lastAlong - 31.0, "jumped back from $lastAlong to ${r.progress.distanceAlong}")
            lastAlong = r.progress.distanceAlong
        }
    }

    @Test
    fun `leaving the route is detected after a few fixes, not on the first glitch`() {
        val tracker = RouteProgressTracker(route)
        val fixes = drive()
        fixes.take(200).forEach { tracker.update(it) }
        val base = fixes[200]
        val offset = { meters: Double -> base.copy(point = Geo.destination(base.point, (base.bearingDeg!! + 90) % 360, meters)) }

        // Jeden „skok“ GPS → ještě na trase.
        assertEquals(TrackingStatus.ON_ROUTE, tracker.update(offset(120.0)).status)
        assertEquals(TrackingStatus.ON_ROUTE, tracker.update(fixes[201]).status)

        // Skutečné sjetí: 3 fixy v řadě 60 m od trasy.
        val results = (1..3).map { tracker.update(offset(60.0)).status }
        assertEquals(listOf(TrackingStatus.ON_ROUTE, TrackingStatus.ON_ROUTE, TrackingStatus.OFF_ROUTE), results)
    }

    @Test
    fun `driving the wrong way is detected even on the route geometry`() {
        val tracker = RouteProgressTracker(route)
        val fixes = drive()
        fixes.take(100).forEach { tracker.update(it) }
        val reversed = fixes.subList(90, 100).reversed().map { it.copy(bearingDeg = (it.bearingDeg!! + 180) % 360) }
        val statuses = reversed.map { tracker.update(it).status }
        assertTrue(TrackingStatus.OFF_ROUTE in statuses)
    }

    @Test
    fun `standing at a junction keeps the arrow still despite gps drift`() {
        val tracker = RouteProgressTracker(route)
        val fixes = drive()
        fixes.take(60).forEach { tracker.update(it) }
        val stop = fixes[60]
        val before = tracker.update(stop.copy(speedMps = 0.0)).progress.distanceAlong
        val rnd = Random(1)
        repeat(60) {
            // GPS u stojícího auta „bloudí“ až 15 m, i směrem dopředu po trase.
            val drift = stop.copy(
                point = Geo.destination(stop.point, stop.bearingDeg!! + rnd.nextDouble(-30.0, 30.0), rnd.nextDouble(0.0, 15.0)),
                speedMps = rnd.nextDouble(0.0, 0.6),
            )
            assertEquals(before, tracker.update(drift).progress.distanceAlong, 1e-9, "arrow moved while standing")
        }
    }

    @Test
    fun `overshoot while braking is corrected after consistent fixes`() {
        val tracker = RouteProgressTracker(route)
        val fixes = drive(every = 1.0)
        fixes.take(120).forEach { tracker.update(it) }
        val ahead = tracker.update(fixes[120]).progress.distanceAlong
        // Skutečná poloha je 20 m zpět (pomalá jízda, auto „předběhlo“ realitu).
        val back = route.geometry.pointAt(ahead - 20)
        val slow = fixes[120].copy(point = back, speedMps = 2.0, accuracyM = 5.0)
        val results = (1..3).map { tracker.update(slow).progress.distanceAlong }
        assertEquals(ahead, results[0], 1e-9)
        assertEquals(ahead - 20, results[2], 1.0)
    }

    @Test
    fun `upcoming index helper`() {
        val ms = route.maneuvers
        assertEquals(1, RouteProgressTracker.upcomingIndex(ms, 0.0))
        assertEquals(ms.lastIndex, RouteProgressTracker.upcomingIndex(ms, route.geometry.length))
        val m3 = ms[3].distanceFromStart
        assertEquals(3, RouteProgressTracker.upcomingIndex(ms, m3 - 10))
        assertEquals(4, RouteProgressTracker.upcomingIndex(ms, m3 + 10))
    }
}
