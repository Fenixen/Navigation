package cz.fenixen.navigation.core.geo

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GeoTest {
    private val brno = GeoPoint(49.1951, 16.6068)
    private val praha = GeoPoint(50.0755, 14.4378)

    @Test
    fun `haversine distance Brno - Praha is about 185 km`() {
        val d = Geo.distance(brno, praha)
        assertEquals(185_000.0, d, 2_000.0)
    }

    @Test
    fun `bearing and destination are inverse operations`() {
        val target = Geo.destination(brno, 42.0, 1_500.0)
        assertEquals(1_500.0, Geo.distance(brno, target), 0.5)
        assertEquals(42.0, Geo.bearing(brno, target), 0.1)
    }

    @Test
    fun `bearing delta wraps around north`() {
        assertEquals(20.0, Geo.bearingDelta(350.0, 10.0), 1e-9)
        assertEquals(-20.0, Geo.bearingDelta(10.0, 350.0), 1e-9)
        assertEquals(180.0, Geo.bearingDelta(0.0, 180.0), 1e-9)
    }

    @Test
    fun `polyline projection finds perpendicular foot and along distance`() {
        val a = brno
        val b = Geo.destination(a, 90.0, 1_000.0) // 1 km na východ
        val line = Polyline(listOf(a, b))
        val probe = Geo.destination(Geo.destination(a, 90.0, 400.0), 0.0, 25.0) // 25 m severně od 400 m
        val proj = line.project(probe)
        assertEquals(400.0, proj.distanceAlong, 1.0)
        assertEquals(25.0, proj.distanceFromLine, 0.5)
        assertEquals(90.0, proj.segmentBearing, 0.5)
    }

    @Test
    fun `pointAt and slice are consistent with cumulative distances`() {
        val pts = listOf(brno, Geo.destination(brno, 0.0, 500.0), Geo.destination(brno, 0.0, 1_200.0))
        val line = Polyline(pts)
        assertEquals(1_200.0, line.length, 0.5)
        val mid = line.pointAt(800.0)
        assertEquals(800.0, Geo.distance(brno, mid), 0.5)
        val slice = line.slice(250.0, 900.0)
        assertEquals(3, slice.size)
        assertEquals(650.0, Polyline(slice).length, 0.5)
        assertEquals(1, line.segmentIndexAt(800.0))
    }

    @Test
    fun `polyline codec round-trips with precision 5 and 6`() {
        val pts = listOf(GeoPoint(49.191003, 16.612352), GeoPoint(49.190884, 16.612044), GeoPoint(-33.86, 151.2))
        for (precision in listOf(5, 6)) {
            val decoded = PolylineCodec.decode(PolylineCodec.encode(pts, precision), precision)
            assertEquals(pts.size, decoded.size)
            pts.zip(decoded).forEach { (e, d) ->
                assertEquals(e.lat, d.lat, 1e-5)
                assertEquals(e.lon, d.lon, 1e-5)
            }
        }
    }

    @Test
    fun `decodes Google reference polyline`() {
        // Příklad z dokumentace Googlu (precision 5).
        val decoded = PolylineCodec.decode("_p~iF~ps|U_ulLnnqC_mqNvxq`@", 5)
        assertEquals(listOf(38.5 to -120.2, 40.7 to -120.95, 43.252 to -126.453), decoded.map { it.lat to it.lon })
    }

    @Test
    fun `bounds contain all points`() {
        val b = GeoBounds.of(listOf(brno, praha))
        assertTrue(b.south <= brno.lat && b.north >= praha.lat && b.west <= praha.lon && b.east >= brno.lon)
    }
}
