package cz.fenixen.navigation.core.search

import cz.fenixen.navigation.core.Fixtures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PhotonParserTest {
    @Test
    fun `parses places with readable address`() {
        val places = PhotonParser.parse(Fixtures.text("photon_search.json"))
        assertTrue(places.isNotEmpty())
        val first = places.first()
        assertEquals("Hladinka a šnyt", first.name)
        assertEquals("Kounicova 10, Brno", first.address)
        assertEquals(49.2017723, first.point.lat, 1e-7)
        assertEquals(16.6019401, first.point.lon, 1e-7)
        val street = places.first { it.category == "tertiary" }
        assertEquals("Kounicova", street.name)
        assertEquals("Brno", street.address)
    }
}
