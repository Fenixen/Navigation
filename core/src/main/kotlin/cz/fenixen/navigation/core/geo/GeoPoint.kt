package cz.fenixen.navigation.core.geo

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** WGS84 point. Latitude/longitude are in degrees. */
data class GeoPoint(val lat: Double, val lon: Double) {
    init {
        require(lat in -90.0..90.0) { "Latitude out of range: $lat" }
        require(lon in -180.0..180.0) { "Longitude out of range: $lon" }
    }

    override fun toString(): String = "(%.6f, %.6f)".format(lat, lon)
}

/** Spherical-earth geodesy helpers. Accuracy (~0.3 %) is more than enough for navigation. */
object Geo {
    /** Mean Earth radius (IUGG), metres. */
    const val EARTH_RADIUS_M = 6_371_008.8

    private const val DEG_TO_RAD = PI / 180.0
    private const val RAD_TO_DEG = 180.0 / PI

    fun toRadians(deg: Double) = deg * DEG_TO_RAD

    fun toDegrees(rad: Double) = rad * RAD_TO_DEG

    /** Great-circle distance in metres (haversine – numerically stable for short distances). */
    fun distance(a: GeoPoint, b: GeoPoint): Double = distance(a.lat, a.lon, b.lat, b.lon)

    fun distance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = (lat2 - lat1) * DEG_TO_RAD
        val dLon = (lon2 - lon1) * DEG_TO_RAD
        val sinLat = sin(dLat / 2)
        val sinLon = sin(dLon / 2)
        val h = sinLat * sinLat + cos(lat1 * DEG_TO_RAD) * cos(lat2 * DEG_TO_RAD) * sinLon * sinLon
        return 2 * EARTH_RADIUS_M * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }

    /** Initial bearing from [from] to [to], degrees in [0, 360). */
    fun bearing(from: GeoPoint, to: GeoPoint): Double {
        val lat1 = from.lat * DEG_TO_RAD
        val lat2 = to.lat * DEG_TO_RAD
        val dLon = (to.lon - from.lon) * DEG_TO_RAD
        val y = sin(dLon) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
        return normalizeBearing(atan2(y, x) * RAD_TO_DEG)
    }

    /** Point reached by travelling [distanceM] from [from] along [bearingDeg]. */
    fun destination(from: GeoPoint, bearingDeg: Double, distanceM: Double): GeoPoint {
        val delta = distanceM / EARTH_RADIUS_M
        val theta = bearingDeg * DEG_TO_RAD
        val lat1 = from.lat * DEG_TO_RAD
        val lon1 = from.lon * DEG_TO_RAD
        val lat2 = asin(sin(lat1) * cos(delta) + cos(lat1) * sin(delta) * cos(theta))
        val lon2 = lon1 + atan2(sin(theta) * sin(delta) * cos(lat1), cos(delta) - sin(lat1) * sin(lat2))
        val lonDeg = ((lon2 * RAD_TO_DEG + 540.0) % 360.0) - 180.0
        return GeoPoint(lat2 * RAD_TO_DEG, lonDeg)
    }

    /** Normalises any angle to [0, 360). */
    fun normalizeBearing(deg: Double): Double {
        val r = deg % 360.0
        return if (r < 0) r + 360.0 else r
    }

    /** Signed smallest difference `to - from` in degrees, range (-180, 180]. */
    fun bearingDelta(from: Double, to: Double): Double {
        var d = (to - from) % 360.0
        if (d <= -180.0) d += 360.0
        if (d > 180.0) d -= 360.0
        return d
    }

    /** Linear interpolation between two points – fine for segment lengths used in routes. */
    fun interpolate(a: GeoPoint, b: GeoPoint, fraction: Double): GeoPoint {
        val f = fraction.coerceIn(0.0, 1.0)
        return GeoPoint(a.lat + (b.lat - a.lat) * f, a.lon + (b.lon - a.lon) * f)
    }
}

/** Axis-aligned bounding box. */
data class GeoBounds(val south: Double, val west: Double, val north: Double, val east: Double) {
    val center: GeoPoint get() = GeoPoint((south + north) / 2, (west + east) / 2)

    companion object {
        fun of(points: Iterable<GeoPoint>): GeoBounds {
            var s = 90.0
            var w = 180.0
            var n = -90.0
            var e = -180.0
            var any = false
            for (p in points) {
                any = true
                if (p.lat < s) s = p.lat
                if (p.lat > n) n = p.lat
                if (p.lon < w) w = p.lon
                if (p.lon > e) e = p.lon
            }
            require(any) { "Cannot compute bounds of an empty collection" }
            return GeoBounds(s, w, n, e)
        }
    }
}
