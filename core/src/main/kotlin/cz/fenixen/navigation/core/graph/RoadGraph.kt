package cz.fenixen.navigation.core.graph

import cz.fenixen.navigation.core.geo.Geo

/**
 * Directed road graph in CSR (Compressed Sparse Row) layout.
 *
 * Why CSR and not `List<List<Edge>>`? All out-edges of a node sit next to each other in
 * primitive arrays → no object headers, no pointer chasing, great CPU-cache locality.
 * A country-sized graph (millions of edges) fits in tens of MB instead of hundreds.
 *
 *   firstEdge[v] until firstEdge[v + 1]  = indices of v's out-edges
 *   edgeTarget[e], edgeWeight[e]          = head node and cost (milliseconds) of edge e
 */
class RoadGraph internal constructor(
    val lat: DoubleArray,
    val lon: DoubleArray,
    internal val firstEdge: IntArray,
    internal val edgeTarget: IntArray,
    internal val edgeWeight: IntArray,
) {
    val nodeCount: Int get() = lat.size
    val edgeCount: Int get() = edgeTarget.size

    fun outDegree(v: Int): Int = firstEdge[v + 1] - firstEdge[v]

    inline fun forEachOutEdge(v: Int, action: (target: Int, weight: Int) -> Unit) {
        for (e in edgeStart(v) until edgeEnd(v)) action(target(e), weight(e))
    }

    fun edgeStart(v: Int): Int = firstEdge[v]
    fun edgeEnd(v: Int): Int = firstEdge[v + 1]
    fun target(e: Int): Int = edgeTarget[e]
    fun weight(e: Int): Int = edgeWeight[e]

    /** Straight-line distance between two nodes, metres. */
    fun distance(a: Int, b: Int): Double = Geo.distance(lat[a], lon[a], lat[b], lon[b])

    /** Graph with every edge flipped – used by backward searches. */
    fun reversed(): RoadGraph {
        val b = Builder(nodeCount)
        for (v in 0 until nodeCount) b.addNode(lat[v], lon[v])
        for (v in 0 until nodeCount) forEachOutEdge(v) { t, w -> b.addEdge(t, v, w) }
        return b.build()
    }

    /**
     * Fastest "straight-line speed" over all edges (m/s). Using it in the A* heuristic
     * h(v) = dist(v, t) / vMax keeps the heuristic admissible *and* consistent.
     */
    fun maxStraightLineSpeed(): Double {
        var best = 1.0
        for (v in 0 until nodeCount) {
            forEachOutEdge(v) { t, w ->
                if (w > 0) {
                    val speed = distance(v, t) / (w / 1000.0)
                    if (speed > best) best = speed
                }
            }
        }
        return best
    }

    /** Mutable builder; [build] sorts edges into CSR with a counting sort (O(n + m)). */
    class Builder(expectedNodes: Int = 16) {
        private var lat = DoubleArray(expectedNodes)
        private var lon = DoubleArray(expectedNodes)
        private var nodes = 0
        private var from = IntArray(expectedNodes * 3)
        private var to = IntArray(expectedNodes * 3)
        private var weight = IntArray(expectedNodes * 3)
        private var edges = 0

        fun addNode(latitude: Double, longitude: Double): Int {
            if (nodes == lat.size) {
                lat = lat.copyOf(nodes * 2 + 1)
                lon = lon.copyOf(nodes * 2 + 1)
            }
            lat[nodes] = latitude
            lon[nodes] = longitude
            return nodes++
        }

        fun addEdge(source: Int, target: Int, weightMs: Int) {
            require(source in 0 until nodes && target in 0 until nodes) { "Unknown node in edge $source→$target" }
            require(weightMs >= 0) { "Negative weights break Dijkstra (and physics)" }
            if (edges == from.size) {
                val cap = edges * 2 + 1
                from = from.copyOf(cap)
                to = to.copyOf(cap)
                weight = weight.copyOf(cap)
            }
            from[edges] = source
            to[edges] = target
            weight[edges] = weightMs
            edges++
        }

        fun addBidirectional(a: Int, b: Int, weightMs: Int) {
            addEdge(a, b, weightMs)
            addEdge(b, a, weightMs)
        }

        fun build(): RoadGraph {
            val first = IntArray(nodes + 1)
            for (i in 0 until edges) first[from[i] + 1]++
            for (v in 0 until nodes) first[v + 1] += first[v]
            val cursor = first.copyOf(nodes)
            val outTarget = IntArray(edges)
            val outWeight = IntArray(edges)
            for (i in 0 until edges) {
                val slot = cursor[from[i]]++
                outTarget[slot] = to[i]
                outWeight[slot] = weight[i]
            }
            return RoadGraph(lat.copyOf(nodes), lon.copyOf(nodes), first, outTarget, outWeight)
        }
    }
}

/** Result of a shortest-path query on a [RoadGraph]. */
class PathResult(
    /** Total cost (travel time) in milliseconds. */
    val cost: Int,
    /** Node sequence from source to target (inclusive). */
    val nodes: IntArray,
    /** How many nodes the search settled – the honest measure of algorithm effort. */
    val settledNodes: Int,
)

/** Common interface so algorithms are interchangeable (Strategy pattern). */
interface ShortestPathAlgorithm {
    val name: String

    /** Shortest path or null when [target] is unreachable from [source]. */
    fun route(source: Int, target: Int): PathResult?
}

internal const val INF = Int.MAX_VALUE
