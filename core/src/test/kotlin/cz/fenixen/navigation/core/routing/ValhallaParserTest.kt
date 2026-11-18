package cz.fenixen.navigation.core.routing

import cz.fenixen.navigation.core.Fixtures
import cz.fenixen.navigation.core.geo.GeoPoint
import cz.fenixen.navigation.core.model.ManeuverType
import cz.fenixen.navigation.core.model.RouteOptions
import cz.fenixen.navigation.core.model.RouteRequest
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ValhallaParserTest {

    @Test
    fun `parses city route with alternative`() {
        val routes = ValhallaParser.parseRouteResponse(Fixtures.text("valhalla_route_brno.json"))
        assertEquals(2, routes.size, "best route + 1 alternate")
        val r = routes.first()
        assertEquals(2_070.0, r.distanceMeters, 1.0)
        assertEquals(526.9, r.durationSeconds, 0.1)
        assertEquals(ManeuverType.DEPART_LEFT, r.maneuvers.first().type)
        assertTrue(r.maneuvers.last().type.isArrival)
        // Geometrická délka musí sedět s délkou od routeru (±2 %).
        assertEquals(r.distanceMeters, r.geometry.length, r.distanceMeters * 0.02)
        // Manévry jsou seřazené podél trasy a poslední je v cíli.
        assertTrue(r.maneuvers.zipWithNext().all { (a, b) -> a.distanceFromStart <= b.distanceFromStart })
        assertEquals(r.geometry.length, r.maneuvers.last().distanceFromStart, 0.01)
        // Valhallí „Poté Odbočte“ je opravené.
        assertTrue(r.maneuvers.none { it.verbalPre?.contains("Poté Odbočte") == true })
    }

    @Test
    fun `parses roundabout exit count`() {
        val r = ValhallaParser.parseRouteResponse(Fixtures.text("valhalla_route_roundabout.json")).first()
        val enter = r.maneuvers.first { it.type == ManeuverType.ROUNDABOUT_ENTER }
        assertEquals(3, enter.roundaboutExitCount)
        assertEquals(ManeuverType.ARRIVE_LEFT, r.maneuvers.last().type)
    }

    @Test
    fun `parses highway route with exits, signs and tolls`() {
        val r = ValhallaParser.parseRouteResponse(Fixtures.text("valhalla_route_highway.json")).first()
        assertTrue(r.hasToll && r.hasHighway)
        val exit = r.maneuvers.first { it.type == ManeuverType.EXIT_RIGHT && it.sign?.exitNumber == "230" }
        assertEquals(listOf("Olomouc", "Ostrava", "Vyškov"), exit.sign?.toward)
        assertTrue(exit.isToll)
        assertTrue(r.summary.contains("D46") || r.summary.contains("D1"), "summary was '${r.summary}'")
    }

    @Test
    fun `parses speed limits from trace_attributes`() {
        val spans = ValhallaParser.mergeSpans(
            ValhallaParser.parseSpeedLimits(Fixtures.text("valhalla_trace_attributes_brno.json"), 0.0),
        )
        assertTrue(spans.isNotEmpty())
        assertEquals(30, spans.first().kph)
        assertTrue(spans.zipWithNext().all { (a, b) -> a.endDistance <= b.startDistance + 1e-6 })
        val route = ValhallaParser.parseRouteResponse(Fixtures.text("valhalla_route_brno.json")).first()
            .let { it.copy(speedLimits = spans) }
        assertEquals(30, route.speedLimitAt(5.0))
        assertNull(route.speedLimitAt(1_000_000.0))
    }

    @Test
    fun `builds request with avoidances and heading`() {
        val req = RouteRequest(
            origin = GeoPoint(49.19, 16.61),
            destination = GeoPoint(49.59, 17.25),
            originBearing = 87.6,
            options = RouteOptions(avoidTolls = true, avoidHighways = false, avoidFerries = true, alternatives = 2),
        )
        val json = ValhallaParser.buildRouteRequest(req, "cs-CZ")
        val origin = json["locations"]!!.jsonArray[0].jsonObject
        assertEquals(87, origin["heading"]!!.jsonPrimitive.int)
        val auto = json["costing_options"]!!.jsonObject["auto"]!!.jsonObject
        assertEquals(0.0, auto["use_tolls"]!!.jsonPrimitive.double)
        assertEquals(1.0, auto["use_highways"]!!.jsonPrimitive.double)
        assertEquals(0.0, auto["use_ferry"]!!.jsonPrimitive.double)
        assertEquals(2, json["alternates"]!!.jsonPrimitive.int)
    }

    @Test
    fun `closures become exclude_locations and via points are through locations`() {
        val req = RouteRequest(
            origin = GeoPoint(49.19, 16.61),
            destination = GeoPoint(49.59, 17.25),
            via = listOf(cz.fenixen.navigation.core.model.ViaPoint(GeoPoint(49.3, 16.9), 45.0)),
            avoid = listOf(GeoPoint(49.2, 16.62)),
        )
        val json = ValhallaParser.buildRouteRequest(req, "cs-CZ")
        val exclude = json["exclude_locations"]!!.jsonArray
        assertEquals(1, exclude.size)
        assertEquals(49.2, exclude[0].jsonObject["lat"]!!.jsonPrimitive.double)
        val via = json["locations"]!!.jsonArray[1].jsonObject
        assertEquals("through", via["type"]!!.jsonPrimitive.content)
        assertEquals(45, via["heading"]!!.jsonPrimitive.int)
        assertNull(json["alternates"], "Valhalla cannot do alternates with via points")
    }

    @Test
    fun `maps no-route error`() {
        val err = ValhallaParser.parseError(
            HttpResult(400, """{"error_code":442,"error":"No path could be found for input","status_code":400}"""),
        )
        assertEquals(RoutingException.Kind.NO_ROUTE, err.kind)
        val other = ValhallaParser.parseError(HttpResult(502, "<html>Bad gateway</html>"))
        assertEquals(RoutingException.Kind.SERVER, other.kind)
        assertFalse(other.message.isNullOrBlank())
    }

    @Test
    fun `maps every documented maneuver code`() {
        for (code in (1..29) + listOf(37, 38)) {
            assertNotNull(ValhallaParser.mapType(code).takeIf { it != ManeuverType.UNKNOWN }, "code $code")
        }
        assertEquals(ManeuverType.UNKNOWN, ValhallaParser.mapType(999))
    }
}
