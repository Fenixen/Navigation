package cz.fenixen.navigation.core.traffic

import cz.fenixen.navigation.core.Fixtures
import cz.fenixen.navigation.core.geo.Geo
import cz.fenixen.navigation.core.model.TrafficLevel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TomTomTrafficTest {
    @Test
    fun `parses travel time, delay and congested sections mapped onto our route`() {
        // Naše zbývající trasa je o 5 % delší než TomTomí → úseky se přepočtou poměrem.
        val theirs = Geo.distance(49.191003, 16.612352, 49.2099, 16.612352)
        val traffic = TomTomTraffic.parse(Fixtures.text("tomtom_route_traffic.json"), fromDistance = 500.0, ourRemainingLength = theirs * 1.05, now = 7)
        assertEquals(480.0, traffic.travelTimeSeconds)
        assertEquals(95.0, traffic.delaySeconds)
        assertEquals(2, traffic.spans.size, "TRAVEL_MODE section must be ignored")
        val jam = traffic.spans[0]
        assertEquals(TrafficLevel.JAM, jam.level)
        assertEquals(12, jam.speedKmh)
        // bod 2 ≈ 444 m od startu TomTomu → 500 + 444 × 1,05
        assertEquals(500.0 + Geo.distance(49.191003, 16.612352, 49.195, 16.612352) * 1.05, jam.startDistance, 1.0)
        assertEquals(TrafficLevel.SLOW, traffic.spans[1].level)
        assertEquals(7, traffic.fetchedAtMillis)
    }

    @Test
    fun `supporting points are capped and keep both ends`() {
        val route = Fixtures.route("valhalla_route_highway.json")
        val points = TomTomTraffic.supportingPoints(route.geometry, fromDistance = 1_000.0)
        assertTrue(points.size <= 401, "was ${points.size}")
        assertEquals(route.geometry.points.last(), points.last())
        assertTrue(Geo.distance(points.first(), route.geometry.pointAt(1_000.0)) < 1.0)
        val body = Json.parseToJsonElement(TomTomTraffic.requestBody(points)).jsonObject
        assertEquals(points.size, body["supportingPoints"]!!.jsonArray.size)
    }
}
