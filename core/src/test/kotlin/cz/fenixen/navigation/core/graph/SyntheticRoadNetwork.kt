package cz.fenixen.navigation.core.graph

import cz.fenixen.navigation.core.geo.Geo
import cz.fenixen.navigation.core.geo.GeoPoint
import kotlin.random.Random

/**
 * Generates a road-like network: a grid of slow local streets, every [arterialEvery]-th line
 * is a faster arterial road and every [highwayEvery]-th a motorway. Some streets are one-way,
 * some are missing. This hierarchy is what makes CH shine on real road networks
 * (a uniform grid would be its worst case).
 */
object SyntheticRoadNetwork {
    fun generate(
        width: Int,
        height: Int,
        seed: Long = 42,
        spacingM: Double = 150.0,
        arterialEvery: Int = 8,
        highwayEvery: Int = 32,
        removeProbability: Double = 0.08,
        oneWayProbability: Double = 0.1,
    ): RoadGraph {
        val rnd = Random(seed)
        val origin = GeoPoint(49.10, 16.45) // jihozápad Brna
        val b = RoadGraph.Builder(width * height)
        val ids = Array(height) { IntArray(width) }
        for (y in 0 until height) {
            val rowStart = Geo.destination(origin, 0.0, y * spacingM)
            for (x in 0 until width) {
                // Malý šum v poloze – silnice nejsou dokonale rovné.
                val p = Geo.destination(rowStart, 90.0, x * spacingM + rnd.nextDouble(-20.0, 20.0))
                ids[y][x] = b.addNode(p.lat, p.lon)
            }
        }

        fun speedKmh(line: Int): Double = when {
            line % highwayEvery == 0 -> 130.0
            line % arterialEvery == 0 -> 80.0
            else -> 40.0 + rnd.nextDouble(-10.0, 10.0)
        }

        fun connect(a: Int, c: Int, line: Int, lat: DoubleArray, lon: DoubleArray) {
            val isMajor = line % arterialEvery == 0
            if (!isMajor && rnd.nextDouble() < removeProbability) return
            val meters = Geo.distance(lat[a], lon[a], lat[c], lon[c]) * rnd.nextDouble(1.0, 1.15)
            val ms = (meters / (speedKmh(line) / 3.6) * 1000).toInt().coerceAtLeast(1)
            if (!isMajor && rnd.nextDouble() < oneWayProbability) {
                if (rnd.nextBoolean()) b.addEdge(a, c, ms) else b.addEdge(c, a, ms)
            } else {
                b.addBidirectional(a, c, ms)
            }
        }

        // Souřadnice potřebujeme pro délky hran → postavíme si je zvlášť.
        val tmp = b.build()
        for (y in 0 until height) for (x in 0 until width) {
            if (x + 1 < width) connect(ids[y][x], ids[y][x + 1], y, tmp.lat, tmp.lon)
            if (y + 1 < height) connect(ids[y][x], ids[y + 1][x], x, tmp.lat, tmp.lon)
        }
        return b.build()
    }

    /** Random directed graph with arbitrary weights – for correctness tests, not realism. */
    fun randomGraph(nodes: Int, edges: Int, seed: Long): RoadGraph {
        val rnd = Random(seed)
        val b = RoadGraph.Builder(nodes)
        repeat(nodes) { b.addNode(49.0 + rnd.nextDouble(0.0, 0.2), 16.0 + rnd.nextDouble(0.0, 0.3)) }
        val g0 = b.build()
        repeat(edges) {
            val u = rnd.nextInt(nodes)
            val v = rnd.nextInt(nodes)
            // Váha ≥ přímá vzdálenost / 40 m/s, aby byla heuristika A* přípustná.
            val minMs = (g0.distance(u, v) / 40.0 * 1000).toInt()
            b.addEdge(u, v, minMs + rnd.nextInt(0, 60_000))
        }
        return b.build()
    }
}
