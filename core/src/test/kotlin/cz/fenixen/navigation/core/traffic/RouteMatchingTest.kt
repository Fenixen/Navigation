package cz.fenixen.navigation.core.traffic

import cz.fenixen.navigation.core.Fixtures
import cz.fenixen.navigation.core.geo.Geo
import cz.fenixen.navigation.core.model.IncidentCategory
import cz.fenixen.navigation.core.model.TrafficIncident
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RouteMatchingTest {
    private val route = Fixtures.route("valhalla_route_highway.json")

    private fun incident(id: String, from: Double, to: Double, reversed: Boolean = false, shiftM: Double = 0.0): TrafficIncident {
        var pts = route.geometry.slice(from, to)
        if (shiftM > 0) pts = pts.map { Geo.destination(it, route.geometry.bearingAt(from) + 90, shiftM) }
        if (reversed) pts = pts.reversed()
        return TrafficIncident(id, IncidentCategory.ACCIDENT, pts)
    }

    @Test
    fun `parses TomTom incident details`() {
        val incidents = TomTomTraffic.parseIncidents(Fixtures.text("tomtom_incidents.json"))
        assertEquals(2, incidents.size, "feature without geometry is dropped")
        val crash = incidents[0]
        assertEquals(IncidentCategory.ACCIDENT, crash.category)
        assertEquals(3, crash.geometry.size)
        assertEquals(49.20, crash.geometry[0].lat)
        assertEquals(540.0, crash.delaySeconds)
        assertEquals(listOf("D1"), crash.roadNumbers)
        assertEquals(IncidentCategory.ROAD_WORKS, incidents[1].category)
        assertEquals(1, incidents[1].geometry.size)
    }

    @Test
    fun `incident on our carriageway matches, opposite direction and parallel road do not`() {
        val ours = incident("ours", 30_000.0, 31_000.0)
        val opposite = incident("opposite", 20_000.0, 21_000.0, reversed = true)
        val parallel = incident("parallel", 10_000.0, 11_000.0, shiftM = 120.0)
        val behind = incident("behind", 2_000.0, 2_500.0)
        val matched = RouteMatching.matchIncidents(route, fromDistance = 5_000.0, incidents = listOf(ours, opposite, parallel, behind))
        assertEquals(listOf("ours"), matched.map { it.incident.id })
        assertEquals(30_000.0, matched.single().distanceAlong, 30.0)
    }

    @Test
    fun `overlap and via points`() {
        val same = route.geometry.slice(10_000.0, 40_000.0)
        assertTrue(RouteMatching.overlap(same, route.geometry, 5_000.0) > 0.95)
        // Objížďka: 2 km od trasy bokem.
        val detour = same.map { Geo.destination(it, 0.0, 2_000.0) }
        assertTrue(RouteMatching.overlap(detour, route.geometry, 5_000.0) < 0.1)
        val via = RouteMatching.viaPoints(detour, route, 5_000.0)
        assertEquals(3, via.size)
        assertTrue(via.all { v -> detour.any { Geo.distance(it, v.point) < 600 } })
        assertTrue(RouteMatching.viaPoints(same, route, 5_000.0).isEmpty(), "no via points for the same road")
    }

    @Test
    fun `query boxes cover the road ahead in chunks`() {
        val boxes = RouteMatching.boundsAhead(route, 1_000.0, aheadM = 80_000.0, chunkM = 40_000.0)
        assertEquals(2, boxes.size)
        val p = route.geometry.pointAt(50_000.0)
        assertTrue(boxes.any { p.lat in it.south..it.north && p.lon in it.west..it.east })
    }
}
