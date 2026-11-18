package cz.fenixen.navigation.core.traffic

import cz.fenixen.navigation.core.geo.Geo
import cz.fenixen.navigation.core.geo.GeoBounds
import cz.fenixen.navigation.core.geo.GeoPoint
import cz.fenixen.navigation.core.geo.Polyline
import cz.fenixen.navigation.core.model.Route
import cz.fenixen.navigation.core.model.RouteIncident
import cz.fenixen.navigation.core.model.TrafficIncident
import cz.fenixen.navigation.core.model.ViaPoint
import kotlin.math.abs

/** Geometry helpers that tie live-traffic data to *our* route. Pure functions → unit-tested. */
object RouteMatching {
    /** Max distance of an incident point from our route to count as "on the route". */
    private const val INCIDENT_TOLERANCE_M = 25.0

    /**
     * Driving direction must agree: carriageways of a motorway are ~20 m apart, so distance alone
     * would report a crash on the opposite side as ours.
     */
    private const val DIRECTION_TOLERANCE_DEG = 60.0

    /** Bounding boxes covering the next [aheadM] metres of the route, in chunks (API limits bbox area). */
    fun boundsAhead(route: Route, fromDistance: Double, aheadM: Double = 80_000.0, chunkM: Double = 40_000.0): List<GeoBounds> {
        val g = route.geometry
        val end = minOf(g.length, fromDistance + aheadM)
        val out = ArrayList<GeoBounds>()
        var a = fromDistance.coerceAtLeast(0.0)
        while (a < end - 1) {
            val b = minOf(end, a + chunkM)
            val box = GeoBounds.of(g.slice(a, b))
            val pad = 0.003 // ~300 m
            out += GeoBounds(box.south - pad, box.west - pad, box.north + pad, box.east + pad)
            a = b
        }
        return out
    }

    /** Incidents lying on the remaining route in our direction, sorted by distance. */
    fun matchIncidents(route: Route, fromDistance: Double, incidents: List<TrafficIncident>): List<RouteIncident> {
        val g = route.geometry
        if (g.segmentCount == 0) return emptyList()
        val fromSeg = g.segmentIndexAt(fromDistance)
        val remaining = GeoBounds.of(g.slice(fromDistance))
        val pad = 0.002
        return incidents.mapNotNull { incident ->
            val pts = incident.geometry
            if (pts.none { it.lat in remaining.south - pad..remaining.north + pad && it.lon in remaining.west - pad..remaining.east + pad }) {
                return@mapNotNull null
            }
            val samples = sample(pts, 12)
            var matched = 0
            var firstAlong = Double.MAX_VALUE
            for ((i, p) in samples.withIndex()) {
                val proj = g.project(p, fromSeg, g.segmentCount - 1)
                if (proj.distanceFromLine > INCIDENT_TOLERANCE_M) continue
                val bearing = when {
                    samples.size < 2 -> null
                    i < samples.size - 1 -> Geo.bearing(p, samples[i + 1])
                    else -> Geo.bearing(samples[i - 1], p)
                }
                if (bearing != null && abs(Geo.bearingDelta(proj.segmentBearing, bearing)) > DIRECTION_TOLERANCE_DEG) continue
                matched++
                if (proj.distanceAlong < firstAlong) firstAlong = proj.distanceAlong
            }
            val needed = if (samples.size == 1) 1 else maxOf(2, samples.size / 2)
            if (matched >= needed && firstAlong >= fromDistance - 50) RouteIncident(incident, firstAlong) else null
        }.sortedBy { it.distanceAlong }
    }

    /** Fraction of [path] (sampled) that lies on our remaining route – 1.0 = the same road. */
    fun overlap(path: List<GeoPoint>, g: Polyline, fromDistance: Double, toleranceM: Double = 40.0): Double {
        if (path.size < 2) return 1.0
        val line = Polyline(path)
        val fromSeg = g.segmentIndexAt(fromDistance)
        val step = maxOf(100.0, line.length / 300)
        var total = 0
        var on = 0
        var d = 0.0
        while (d <= line.length) {
            total++
            if (g.project(line.pointAt(d), fromSeg, g.segmentCount - 1).distanceFromLine <= toleranceM) on++
            d += step
        }
        return if (total == 0) 1.0 else on.toDouble() / total
    }

    /**
     * Pass-through points (with direction) that force Valhalla onto the traffic router's path,
     * placed only where that path *leaves* our current route.
     */
    fun viaPoints(path: List<GeoPoint>, route: Route, fromDistance: Double, count: Int = 3): List<ViaPoint> {
        val line = Polyline(path)
        val g = route.geometry
        val fromSeg = g.segmentIndexAt(fromDistance)
        val step = maxOf(100.0, line.length / 300)
        val offRoute = ArrayList<Double>()
        var d = step
        while (d < line.length - step) {
            if (g.project(line.pointAt(d), fromSeg, g.segmentCount - 1).distanceFromLine > 60.0) offRoute += d
            d += step
        }
        if (offRoute.isEmpty()) return emptyList()
        val first = offRoute.first()
        val last = offRoute.last()
        return (1..count).map { i ->
            val at = first + (last - first) * i / (count + 1)
            ViaPoint(line.pointAt(at), line.bearingAt(at))
        }
    }

    private fun sample(points: List<GeoPoint>, max: Int): List<GeoPoint> {
        if (points.size <= max) return points
        val step = (points.size - 1).toDouble() / (max - 1)
        return (0 until max).map { points[(it * step).toInt().coerceAtMost(points.lastIndex)] }
    }
}
