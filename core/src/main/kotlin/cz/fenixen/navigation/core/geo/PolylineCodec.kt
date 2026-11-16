package cz.fenixen.navigation.core.geo

import kotlin.math.pow
import kotlin.math.roundToLong

/**
 * Google "Encoded Polyline Algorithm Format".
 * Valhalla uses precision 6 (1e-6°, ~11 cm), Google/OSRM precision 5.
 *
 * Under the hood: each coordinate is delta-encoded against the previous one, zig-zag encoded
 * (sign moved to the lowest bit) and split into 5-bit chunks stored as printable ASCII.
 */
object PolylineCodec {

    fun decode(encoded: String, precision: Int = 6): List<GeoPoint> {
        val factor = 10.0.pow(precision)
        val result = ArrayList<GeoPoint>(encoded.length / 4)
        var index = 0
        var lat = 0L
        var lon = 0L
        while (index < encoded.length) {
            val (dLat, next1) = decodeValue(encoded, index)
            val (dLon, next2) = decodeValue(encoded, next1)
            index = next2
            lat += dLat
            lon += dLon
            result += GeoPoint(lat / factor, lon / factor)
        }
        return result
    }

    fun encode(points: List<GeoPoint>, precision: Int = 6): String {
        val factor = 10.0.pow(precision)
        val sb = StringBuilder(points.size * 8)
        var prevLat = 0L
        var prevLon = 0L
        for (p in points) {
            val lat = (p.lat * factor).roundToLong()
            val lon = (p.lon * factor).roundToLong()
            encodeValue(lat - prevLat, sb)
            encodeValue(lon - prevLon, sb)
            prevLat = lat
            prevLon = lon
        }
        return sb.toString()
    }

    private fun decodeValue(s: String, start: Int): Pair<Long, Int> {
        var result = 0L
        var shift = 0
        var i = start
        while (true) {
            require(i < s.length) { "Malformed polyline at index $i" }
            val b = s[i++].code - 63
            result = result or ((b and 0x1f).toLong() shl shift)
            shift += 5
            if (b < 0x20) break
        }
        val value = if (result and 1L != 0L) (result shr 1).inv() else result shr 1
        return value to i
    }

    private fun encodeValue(value: Long, sb: StringBuilder) {
        var v = if (value < 0) (value shl 1).inv() else value shl 1
        while (v >= 0x20) {
            sb.append(((0x20 or (v and 0x1f).toInt()) + 63).toChar())
            v = v shr 5
        }
        sb.append((v + 63).toInt().toChar())
    }
}
