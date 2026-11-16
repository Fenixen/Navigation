package cz.fenixen.navigation.core.format

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** Czech formatting for the UI and for spoken instructions. */
object CzechFormat {

    private val clockFormatter = DateTimeFormatter.ofPattern("H:mm")

    /** Banner/UI distance: "40 m", "350 m", "1,2 km", "12 km". */
    fun distance(meters: Double): String {
        val m = meters.coerceAtLeast(0.0)
        return when {
            m < 50 -> "${roundTo(m, 5.0)} m"
            m < 300 -> "${roundTo(m, 10.0)} m"
            m < 950 -> "${roundTo(m, 50.0)} m"
            m < 9_950 -> "${decimal(m / 1000.0)} km"
            else -> "${(m / 1000.0).roundToInt()} km"
        }
    }

    /** "< 1 min", "12 min", "1 h 05 min". */
    fun duration(seconds: Double): String {
        val totalMinutes = (seconds / 60.0).roundToLong()
        if (totalMinutes < 1) return "< 1 min"
        val h = totalMinutes / 60
        val min = totalMinutes % 60
        return if (h == 0L) "$min min" else String.format(Locale.ROOT, "%d h %02d min", h, min)
    }

    fun clock(epochMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        clockFormatter.format(Instant.ofEpochMilli(epochMillis).atZone(zone))

    /**
     * Distance for speech in accusative ("Za 300 metrů", "Pokračujte 2 kilometry").
     * Czech plural rules: 1 kilometr, 2–4 kilometry, 5+ kilometrů, fraction → kilometru.
     */
    fun spokenDistance(meters: Double): String {
        val m = meters.coerceAtLeast(0.0)
        if (m < 950) {
            val rounded = when {
                m < 100 -> roundTo(m, 10.0).coerceAtLeast(10)
                m < 500 -> roundTo(m, 50.0)
                else -> roundTo(m, 100.0)
            }
            return "$rounded ${pluralMeters(rounded)}"
        }
        val km = m / 1000.0
        val roundedKm = if (km < 10) (km * 2).roundToInt() / 2.0 else km.roundToInt().toDouble()
        val isWhole = abs(roundedKm - roundedKm.roundToInt()) < 1e-9
        return if (isWhole) {
            val n = roundedKm.roundToInt()
            "$n ${pluralKilometers(n)}"
        } else {
            "${decimal(roundedKm)} kilometru"
        }
    }

    private fun pluralMeters(n: Int) = when (n) {
        1 -> "metr"
        in 2..4 -> "metry"
        else -> "metrů"
    }

    private fun pluralKilometers(n: Int) = when (n) {
        1 -> "kilometr"
        in 2..4 -> "kilometry"
        else -> "kilometrů"
    }

    private fun roundTo(value: Double, step: Double): Int = ((value / step).roundToInt() * step).toInt()

    private fun decimal(value: Double): String = String.format(Locale.ROOT, "%.1f", value).replace('.', ',')
}

/** Clean-up of Valhalla's cs-CZ narrative ("Poté Odbočte" → "Poté odbočte"). */
object CzechText {
    private val afterThen = Regex("""Poté(?: za ([^,.]+),)? (\p{Lu})""")

    fun normalizeValhalla(text: String): String =
        afterThen.replace(text) { mr ->
            val distance = mr.groups[1]?.value
            val letter = mr.groups[2]!!.value.lowercase()
            if (distance != null) "Poté za $distance $letter" else "Poté $letter"
        }

    /** "Odbočte vpravo." → "odbočte vpravo." for use after a prefix like "Za 300 metrů". */
    fun decapitalize(text: String): String =
        if (text.isEmpty()) text else text.substring(0, 1).lowercase() + text.substring(1)
}
