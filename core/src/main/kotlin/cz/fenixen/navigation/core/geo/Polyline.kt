package cz.fenixen.navigation.core.geo

import kotlin.math.cos
import kotlin.math.sqrt

/**
 * Immutable polyline with pre-computed cumulative distances.
 *
 * Cumulative distances make "distance along route" queries O(log n) (binary search),
 * which matters because the tracker asks them once per GPS fix for the whole drive.
 */
class Polyline(val points: List<GeoPoint>) {
    init {
        require(points.isNotEmpty()) { "Polyline needs at least one point" }
    }

    /** cumulative[i] = distance from points[0] to points[i] along the line, metres. */
    val cumulative: DoubleArray = DoubleArray(points.size).also { acc ->
        for (i in 1 until points.size) acc[i] = acc[i - 1] + Geo.distance(points[i - 1], points[i])
    }

    val length: Double get() = cumulative.last()

    val segmentCount: Int get() = (points.size - 1).coerceAtLeast(0)

    /** Index of the segment containing [distance] (clamped to the line). */
    fun segmentIndexAt(distance: Double): Int {
        if (segmentCount == 0) return 0
        if (distance <= 0.0) return 0
        if (distance >= length) return segmentCount - 1
        // Binary search: largest i with cumulative[i] <= distance.
        var lo = 0
        var hi = points.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (cumulative[mid] <= distance) lo = mid else hi = mid - 1
        }
        return lo.coerceAtMost(segmentCount - 1)
    }

    fun pointAt(distance: Double): GeoPoint {
        if (segmentCount == 0) return points[0]
        val i = segmentIndexAt(distance)
        val segLen = cumulative[i + 1] - cumulative[i]
        val f = if (segLen <= 0.0) 0.0 else (distance - cumulative[i]) / segLen
        return Geo.interpolate(points[i], points[i + 1], f)
    }

    fun bearingAt(distance: Double): Double {
        if (segmentCount == 0) return 0.0
        var i = segmentIndexAt(distance)
        // Skip zero-length segments (duplicated vertices are common in router output).
        while (i < segmentCount - 1 && cumulative[i + 1] - cumulative[i] < 0.01) i++
        return Geo.bearing(points[i], points[i + 1])
    }

    /** Sub-line between two along-route distances (inclusive end points interpolated). */
    fun slice(fromDistance: Double, toDistance: Double = length): List<GeoPoint> {
        val from = fromDistance.coerceIn(0.0, length)
        val to = toDistance.coerceIn(from, length)
        if (segmentCount == 0) return listOf(points[0])
        val startSeg = segmentIndexAt(from)
        val endSeg = segmentIndexAt(to)
        val out = ArrayList<GeoPoint>(endSeg - startSeg + 3)
        out += pointAt(from)
        for (i in startSeg + 1..endSeg) out += points[i]
        out += pointAt(to)
        return out
    }

    /**
     * Projects [p] onto segments [fromSegment]..[toSegment] and returns the closest projection.
     * [scorer] lets the caller add a penalty (e.g. heading mismatch) to the plain distance.
     */
    fun project(
        p: GeoPoint,
        fromSegment: Int = 0,
        toSegment: Int = segmentCount - 1,
        scorer: ((Projection) -> Double)? = null,
    ): Projection {
        if (segmentCount == 0) {
            return Projection(0, 0.0, 0.0, Geo.distance(p, points[0]), points[0], 0.0)
        }
        val from = fromSegment.coerceIn(0, segmentCount - 1)
        val to = toSegment.coerceIn(from, segmentCount - 1)
        var best: Projection? = null
        var bestScore = Double.MAX_VALUE
        for (i in from..to) {
            val proj = projectOnSegment(p, i)
            val score = scorer?.invoke(proj) ?: proj.distanceFromLine
            if (score < bestScore) {
                bestScore = score
                best = proj
            }
        }
        return best!!
    }

    /**
     * Projection onto a single segment using a local equirectangular approximation.
     * Error is negligible for segment lengths of a few km, and it's ~5x cheaper than spherical math.
     */
    fun projectOnSegment(p: GeoPoint, index: Int): Projection {
        val a = points[index]
        val b = points[index + 1]
        val cosLat = cos(Geo.toRadians(a.lat))
        // Local metric coordinates with origin in `a`.
        val bx = Geo.toRadians(b.lon - a.lon) * cosLat * Geo.EARTH_RADIUS_M
        val by = Geo.toRadians(b.lat - a.lat) * Geo.EARTH_RADIUS_M
        val px = Geo.toRadians(p.lon - a.lon) * cosLat * Geo.EARTH_RADIUS_M
        val py = Geo.toRadians(p.lat - a.lat) * Geo.EARTH_RADIUS_M
        val len2 = bx * bx + by * by
        val t = if (len2 <= 1e-9) 0.0 else ((px * bx + py * by) / len2).coerceIn(0.0, 1.0)
        val dx = px - t * bx
        val dy = py - t * by
        val segLength = cumulative[index + 1] - cumulative[index]
        return Projection(
            segmentIndex = index,
            fraction = t,
            distanceAlong = cumulative[index] + t * segLength,
            distanceFromLine = sqrt(dx * dx + dy * dy),
            point = Geo.interpolate(a, b, t),
            segmentBearing = if (len2 <= 1e-9) 0.0 else Geo.bearing(a, b),
        )
    }
}

data class Projection(
    val segmentIndex: Int,
    /** Position within the segment, 0..1. */
    val fraction: Double,
    /** Distance from the polyline start to the projected point, metres. */
    val distanceAlong: Double,
    /** Perpendicular distance between the input point and the polyline, metres. */
    val distanceFromLine: Double,
    val point: GeoPoint,
    val segmentBearing: Double,
)
