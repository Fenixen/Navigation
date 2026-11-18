package cz.fenixen.navigation.core.navigation

import cz.fenixen.navigation.core.Fixtures
import cz.fenixen.navigation.core.geo.Geo
import cz.fenixen.navigation.core.model.LocationFix
import cz.fenixen.navigation.core.model.Place
import cz.fenixen.navigation.core.model.Route
import cz.fenixen.navigation.core.model.RouteRequest
import cz.fenixen.navigation.core.geo.GeoBounds
import cz.fenixen.navigation.core.geo.GeoPoint
import cz.fenixen.navigation.core.model.AvoidArea
import cz.fenixen.navigation.core.model.IncidentCategory
import cz.fenixen.navigation.core.model.RouteOptions
import cz.fenixen.navigation.core.model.RouteTraffic
import cz.fenixen.navigation.core.model.TrafficIncident
import cz.fenixen.navigation.core.model.TrafficRoute
import cz.fenixen.navigation.core.model.SpeedLimitSpan
import cz.fenixen.navigation.core.model.TrafficLevel
import cz.fenixen.navigation.core.model.TrafficSpan
import cz.fenixen.navigation.core.traffic.TrafficProvider
import cz.fenixen.navigation.core.routing.RoutingException
import cz.fenixen.navigation.core.routing.RoutingProvider
import cz.fenixen.navigation.core.simulation.RouteSimulator
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class NavigationSessionTest {
    private val highway = Fixtures.route("valhalla_route_highway.json")
    private val city = Fixtures.text("valhalla_route_brno.json")
    private val destination = Place("Olomouc", highway.destination)

    private class FakeRouter(var routes: List<Route>, var limits: List<SpeedLimitSpan> = emptyList()) : RoutingProvider {
        val requests = mutableListOf<RouteRequest>()
        var fail: RoutingException? = null

        override suspend fun route(request: RouteRequest): List<Route> {
            requests += request
            fail?.let { throw it }
            return routes
        }

        override suspend fun speedLimits(route: Route) = limits
    }

    private fun TestScope.collectEvents(session: NavigationSession): MutableList<NavigationEvent> {
        val events = mutableListOf<NavigationEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { session.events.toList(events) }
        return events
    }

    private fun startFix(route: Route) = RouteSimulator(route).fixAt(0.0, 0)!!

    @Test
    fun `plan, preview, start, drive and arrive`() = runTest {
        val router = FakeRouter(cz.fenixen.navigation.core.routing.ValhallaParser.parseRouteResponse(city))
        val route = router.routes.first()
        val session = NavigationSession(router, this, clock = { testScheduler.currentTime })
        val events = collectEvents(session)

        session.planRoute(destination)
        assertIs<NavigationState.Failed>(session.state.value, "no GPS yet")

        session.onLocation(startFix(route).copy(speedMps = 0.0))
        session.planRoute(destination)
        advanceUntilIdle()
        val preview = assertIs<NavigationState.Preview>(session.state.value)
        assertEquals(2, preview.routes.size)
        session.selectRoute(1)
        session.selectRoute(0)

        session.startNavigation()
        assertIs<NavigationState.Navigating>(session.state.value)
        val sim = RouteSimulator(route)
        var t = 0.0
        while (session.state.value is NavigationState.Navigating) {
            session.onLocation(sim.fixAt(t, 0) ?: sim.fixAt(sim.totalSeconds, 0)!!)
            t += 1.0
            assertTrue(t < sim.totalSeconds + 30, "never arrived")
        }
        assertIs<NavigationState.Arrived>(session.state.value)
        assertEquals(NavigationEvent.NavigationStarted, events.first())
        assertEquals(NavigationEvent.Arrived, events.last())
        assertTrue(events.filterIsInstance<NavigationEvent.Speak>().size >= route.maneuvers.size - 1)
        assertEquals(null, router.requests.first().originBearing, "standing still → no heading constraint")
    }

    @Test
    fun `going off route triggers a reroute from the current position and heading`() = runTest {
        val router = FakeRouter(listOf(highway))
        val session = NavigationSession(router, this, clock = { testScheduler.currentTime })
        val events = collectEvents(session)
        session.onLocation(startFix(highway))
        session.planRoute(destination, autoStart = true)
        advanceUntilIdle()
        assertIs<NavigationState.Navigating>(session.state.value)

        val sim = RouteSimulator(highway)
        for (t in 0..300 step 2) session.onLocation(sim.fixAt(t.toDouble(), 0)!!)
        val last = sim.fixAt(300.0, 0)!!
        val off = { m: Double ->
            last.copy(point = Geo.destination(last.point, (last.bearingDeg!! + 90) % 360, m), bearingDeg = 10.0)
        }
        repeat(3) { session.onLocation(off(100.0 + it)) }
        assertTrue((session.state.value as NavigationState.Navigating).isRerouting)
        advanceUntilIdle()

        assertTrue(NavigationEvent.Rerouted in events)
        assertEquals(2, router.requests.size)
        val reroute = router.requests.last()
        assertEquals(10.0, reroute.originBearing)
        assertEquals(0, reroute.options.alternatives, "reroute does not need alternatives")
        assertTrue(Geo.distance(reroute.origin, off(102.0).point) < 1.0)
    }

    @Test
    fun `failed reroute keeps guiding on the old route`() = runTest {
        val router = FakeRouter(listOf(highway))
        val session = NavigationSession(router, this, clock = { testScheduler.currentTime })
        session.onLocation(startFix(highway))
        session.planRoute(destination, autoStart = true)
        advanceUntilIdle()
        router.fail = RoutingException(RoutingException.Kind.NETWORK, "offline")
        val fix = startFix(highway)
        repeat(3) { session.onLocation(fix.copy(point = Geo.destination(fix.point, 0.0, 500.0))) }
        advanceUntilIdle()
        val nav = assertIs<NavigationState.Navigating>(session.state.value)
        assertTrue(nav.rerouteFailed)
        assertEquals(highway, nav.route.copy(speedLimits = emptyList()))
    }

    @Test
    fun `speed limits are attached after start and speeding is flagged`() = runTest {
        val limits = listOf(SpeedLimitSpan(0.0, highway.geometry.length, 50))
        val router = FakeRouter(listOf(highway), limits)
        val session = NavigationSession(router, this, clock = { testScheduler.currentTime })
        val events = collectEvents(session)
        session.onLocation(startFix(highway))
        session.planRoute(destination, autoStart = true)
        advanceUntilIdle()
        val nav = assertIs<NavigationState.Navigating>(session.state.value)
        assertEquals(limits, nav.route.speedLimits)

        val fast: LocationFix = startFix(highway).copy(speedMps = 70 / 3.6)
        session.onLocation(fast)
        val after = assertIs<NavigationState.Navigating>(session.state.value)
        assertEquals(50, assertNotNull(after.progress).speedLimitKph)
        assertTrue(after.isSpeeding)
        assertTrue(events.any { it is NavigationEvent.SpeedLimitExceeded && it.limitKph == 50 })
    }

    private class FakeTraffic(var delay: Double) : TrafficProvider {
        val calls = mutableListOf<Double>()
        override suspend fun traffic(route: Route, fromDistance: Double): RouteTraffic {
            calls += fromDistance
            val spans = listOf(TrafficSpan(fromDistance + 1_000, fromDistance + 3_000, TrafficLevel.JAM, delay))
            return RouteTraffic(fromDistance, route.durationSeconds * 0.8 + delay, delay, spans, 0)
        }
    }

    @Test
    fun `traffic colours the route, corrects ETA, refreshes and announces big delays`() = runTest {
        val router = FakeRouter(listOf(highway))
        val traffic = FakeTraffic(delay = 60.0)
        val session = NavigationSession(router, this, clock = { testScheduler.currentTime }, traffic = { traffic }, trafficRefreshMs = 180_000)
        val events = collectEvents(session)
        session.onLocation(startFix(highway).copy(speedMps = 0.0))
        session.planRoute(destination)
        runCurrent()
        val preview = assertIs<NavigationState.Preview>(session.state.value)
        assertEquals(60.0, preview.routes.first().traffic?.delaySeconds, "preview shows traffic")

        session.startNavigation()
        runCurrent()
        session.onLocation(startFix(highway).copy(speedMps = 0.0))
        val nav = assertIs<NavigationState.Navigating>(session.state.value)
        assertEquals(1, nav.route.traffic?.spans?.size)
        // Doprava: 0,8 × router + 60 s zdržení → ETA kratší než surový odhad routeru.
        assertEquals(highway.durationSeconds * 0.8 + 60, nav.progress!!.durationRemaining, 30.0)

        traffic.delay = 900.0
        advanceTimeBy(180_001)
        runCurrent()
        assertEquals(3, traffic.calls.size, "preview + navigation start + refresh after 3 minutes")
        assertTrue(events.any { it is NavigationEvent.Speak && it.text.contains("zdržení asi 15 minut") })
        session.stop()
    }

    private class IncidentTraffic(
        private val incidents: List<TrafficIncident>,
        private val fastest: TrafficRoute?,
    ) : TrafficProvider {
        override suspend fun traffic(route: Route, fromDistance: Double) =
            RouteTraffic(fromDistance, route.durationSeconds, 0.0, emptyList(), 0)
        override suspend fun incidents(bounds: GeoBounds) = incidents
        override suspend fun fastestRoute(origin: GeoPoint, bearing: Double?, destination: GeoPoint, options: RouteOptions) = fastest
    }

    @Test
    fun `incidents ahead are shown and announced once`() = runTest {
        val crash = TrafficIncident("crash", IncidentCategory.ACCIDENT, highway.geometry.slice(2_200.0, 2_800.0), delaySeconds = 300.0)
        val router = FakeRouter(listOf(highway))
        val session = NavigationSession(router, this, clock = { testScheduler.currentTime }, traffic = { IncidentTraffic(listOf(crash), null) })
        val events = collectEvents(session)
        session.onLocation(startFix(highway))
        session.planRoute(destination, autoStart = true)
        runCurrent()
        val nav = assertIs<NavigationState.Navigating>(session.state.value)
        assertEquals("crash", nav.nextIncident?.incident?.id)

        val sim = RouteSimulator(highway)
        for (t in 0..200) session.onLocation(sim.fixAt(t.toDouble(), 0)!!)
        val warnings = events.filterIsInstance<NavigationEvent.Speak>().filter { it.text.contains("nehoda") }
        assertEquals(1, warnings.size, "announced exactly once: $warnings")
        assertTrue(warnings.single().text.contains("Zdržení asi 5 minut"))
        session.stop()
    }

    @Test
    fun `faster route is offered and can be accepted`() = runTest {
        val cityRoutes = cz.fenixen.navigation.core.routing.ValhallaParser.parseRouteResponse(city)
        val detour = cityRoutes.first()
        val router = object : RoutingProvider {
            val requests = mutableListOf<RouteRequest>()
            override suspend fun route(request: RouteRequest): List<Route> {
                requests += request
                return listOf(if (request.via.isEmpty()) highway else detour)
            }
        }
        val fastest = TrafficRoute(detour.geometry.points, travelTimeSeconds = 600.0, delaySeconds = 0.0)
        val session = NavigationSession(router, this, clock = { testScheduler.currentTime }, traffic = { IncidentTraffic(emptyList(), fastest) })
        val events = collectEvents(session)
        session.onLocation(startFix(highway))
        session.planRoute(destination, autoStart = true)
        runCurrent()
        val offer = assertNotNull(assertIs<NavigationState.Navigating>(session.state.value).fasterRoute)
        assertTrue(offer.savingSeconds > 300)
        assertEquals(3, router.requests.last().via.size, "Valhalla follows TomTom's path via pass-through points")
        assertTrue(events.any { it is NavigationEvent.Speak && it.text.startsWith("Je k dispozici rychlejší trasa") })

        session.acceptFasterRoute()
        val nav = assertIs<NavigationState.Navigating>(session.state.value)
        assertEquals(detour.geometry, nav.route.geometry)
        assertEquals(null, nav.fasterRoute)
        session.stop()
    }

    @Test
    fun `dismissed offer is not repeated for a while`() = runTest {
        val detour = cz.fenixen.navigation.core.routing.ValhallaParser.parseRouteResponse(city).first()
        val router = object : RoutingProvider {
            override suspend fun route(request: RouteRequest) = listOf(if (request.via.isEmpty()) highway else detour)
        }
        val fastest = TrafficRoute(detour.geometry.points, 600.0, 0.0)
        val session = NavigationSession(router, this, clock = { testScheduler.currentTime }, traffic = { IncidentTraffic(emptyList(), fastest) }, trafficRefreshMs = 180_000)
        session.onLocation(startFix(highway))
        session.planRoute(destination, autoStart = true)
        runCurrent()
        assertNotNull(assertIs<NavigationState.Navigating>(session.state.value).fasterRoute)
        session.dismissFasterRoute()
        advanceTimeBy(180_001)
        runCurrent()
        assertEquals(null, assertIs<NavigationState.Navigating>(session.state.value).fasterRoute, "snoozed for 10 minutes")
        session.stop()
    }

    @Test
    fun `closure on the planned route is avoided before the driver even starts`() = runTest {
        val closure = TrafficIncident("closed", IncidentCategory.ROAD_CLOSED, highway.geometry.slice(20_000.0, 21_000.0))
        val detour = cz.fenixen.navigation.core.routing.ValhallaParser.parseRouteResponse(city).first()
        val router = object : RoutingProvider {
            val requests = mutableListOf<RouteRequest>()
            override suspend fun route(request: RouteRequest): List<Route> {
                requests += request
                return listOf(if (request.avoid.isEmpty()) highway else detour)
            }
        }
        val session = NavigationSession(router, this, clock = { testScheduler.currentTime }, traffic = { IncidentTraffic(listOf(closure), null) })
        session.onLocation(startFix(highway).copy(speedMps = 0.0))
        session.planRoute(destination)
        runCurrent()
        val preview = assertIs<NavigationState.Preview>(session.state.value)
        assertEquals(detour.geometry, preview.selected.geometry, "route goes around the closure")
        assertEquals(1, preview.avoidedClosures)
        val avoid = router.requests.last().avoid
        assertTrue(avoid.isNotEmpty() && avoid.all { p -> closure.geometry.any { Geo.distance(it, p) < 200 } })
        session.stop()
    }

    @Test
    fun `reporting a closure blocks the road ahead and reroutes immediately`() = runTest {
        val router = FakeRouter(listOf(highway))
        val session = NavigationSession(router, this, clock = { testScheduler.currentTime })
        val events = collectEvents(session)
        session.onLocation(startFix(highway))
        session.planRoute(destination, autoStart = true)
        advanceUntilIdle()
        val sim = RouteSimulator(highway)
        for (t in 0..60) session.onLocation(sim.fixAt(t.toDouble(), 0)!!)
        val along = assertIs<NavigationState.Navigating>(session.state.value).progress!!.distanceAlong

        session.reportClosureAhead()
        advanceUntilIdle()
        val area = session.avoidAreas.value.single()
        assertEquals(AvoidArea.Source.MANUAL, area.source)
        assertTrue(area.points.all { p -> highway.geometry.project(p).distanceAlong > along + 30 }, "blocks only the road ahead")
        assertEquals(area.points, router.requests.last().avoid, "reroute request carries the closure")
        assertTrue(events.any { it is NavigationEvent.Speak && it.text.contains("objížďku") })
        session.stop()
    }

    @Test
    fun `closures that make the destination unreachable are ignored rather than failing`() = runTest {
        val router = object : RoutingProvider {
            override suspend fun route(request: RouteRequest): List<Route> {
                if (request.avoid.isNotEmpty()) throw RoutingException(RoutingException.Kind.NO_ROUTE, "none")
                return listOf(highway)
            }
        }
        val session = NavigationSession(router, this, clock = { testScheduler.currentTime })
        session.restoreAvoids(listOf(AvoidArea("x", listOf(highway.destination), Long.MAX_VALUE, AvoidArea.Source.MANUAL)))
        session.onLocation(startFix(highway))
        session.planRoute(destination)
        advanceUntilIdle()
        assertIs<NavigationState.Preview>(session.state.value)
    }

    @Test
    fun `routing failure is reported and can be retried`() = runTest {
        val router = FakeRouter(listOf(highway))
        router.fail = RoutingException(RoutingException.Kind.NO_ROUTE, "none")
        val session = NavigationSession(router, this)
        session.onLocation(startFix(highway))
        session.planRoute(destination)
        advanceUntilIdle()
        assertEquals(FailureReason.NO_ROUTE, assertIs<NavigationState.Failed>(session.state.value).reason)
        router.fail = null
        session.retry()
        advanceUntilIdle()
        assertIs<NavigationState.Preview>(session.state.value)
        session.stop()
        assertEquals(NavigationState.Idle, session.state.value)
    }
}
