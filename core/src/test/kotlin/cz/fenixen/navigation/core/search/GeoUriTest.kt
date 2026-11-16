package cz.fenixen.navigation.core.search

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GeoUriTest {
    @Test
    fun `plain coordinates`() {
        val g = GeoUri.parse("geo:49.1951,16.6068")!!
        assertEquals(49.1951, g.point!!.lat)
        assertEquals(16.6068, g.point.lon)
        assertNull(g.query)
    }

    @Test
    fun `address query with zero coordinates`() {
        val g = GeoUri.parse("geo:0,0?q=Kounicova%2010%2C%20Brno")!!
        assertNull(g.point)
        assertEquals("Kounicova 10, Brno", g.query)
    }

    @Test
    fun `labelled coordinates in query win`() {
        val g = GeoUri.parse("geo:0,0?q=49.19,16.61(Hlavní nádraží)")!!
        assertEquals(49.19, g.point!!.lat)
        assertEquals("Hlavní nádraží", g.label)
    }

    @Test
    fun `invalid input`() {
        assertNull(GeoUri.parse("https://example.com"))
        assertNull(GeoUri.parse("geo:0,0"))
        assertNull(GeoUri.parse("geo:999,999"))
    }
}
