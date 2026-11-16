package cz.fenixen.navigation.core.format

import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals

class CzechFormatTest {
    @Test
    fun `ui distances`() {
        assertEquals("40 m", CzechFormat.distance(38.0))
        assertEquals("350 m", CzechFormat.distance(347.0))
        assertEquals("1,2 km", CzechFormat.distance(1_234.0))
        assertEquals("12 km", CzechFormat.distance(12_345.0))
    }

    @Test
    fun `spoken distances use correct czech plural forms`() {
        assertEquals("80 metrů", CzechFormat.spokenDistance(83.0))
        assertEquals("300 metrů", CzechFormat.spokenDistance(310.0))
        assertEquals("1 kilometr", CzechFormat.spokenDistance(1_020.0))
        assertEquals("1,5 kilometru", CzechFormat.spokenDistance(1_480.0))
        assertEquals("2 kilometry", CzechFormat.spokenDistance(2_100.0))
        assertEquals("4 kilometry", CzechFormat.spokenDistance(3_871.0))
        assertEquals("26 kilometrů", CzechFormat.spokenDistance(26_280.0))
    }

    @Test
    fun `durations`() {
        assertEquals("< 1 min", CzechFormat.duration(20.0))
        assertEquals("9 min", CzechFormat.duration(526.9))
        assertEquals("1 h 05 min", CzechFormat.duration(3_900.0))
    }

    @Test
    fun `clock in given zone`() {
        // 2026-11-16T13:05:00Z = 14:05 v Praze (SEČ)
        assertEquals("14:05", CzechFormat.clock(1_794_834_300_000L, ZoneId.of("Europe/Prague")))
    }

    @Test
    fun `fixes valhalla capitalisation after Pote`() {
        assertEquals(
            "Jeďte na jihozápad. Poté za 90 metrů odbočte vpravo.",
            CzechText.normalizeValhalla("Jeďte na jihozápad. Poté za 90 metrů, Odbočte vpravo."),
        )
        assertEquals(
            "Odbočte vpravo. Poté odbočte vpravo na Nádražní.",
            CzechText.normalizeValhalla("Odbočte vpravo. Poté Odbočte vpravo na Nádražní."),
        )
        assertEquals("Na rozdvojení se držte vlevo.", CzechText.normalizeValhalla("Na rozdvojení se držte vlevo."))
    }
}
