package cz.fenixen.navigation.core.graph

/**
 * Classic Dijkstra – the baseline. Explores a "ball" around the source in order of distance,
 * so it settles roughly every node closer than the target: on a country graph that's
 * millions of nodes for a long trip. Correct, simple, but too slow for production routing.
 */
class Dijkstra(private val graph: RoadGraph) : ShortestPathAlgorithm {
    override val name = "Dijkstra"
    private val space = SearchSpace(graph.nodeCount)

    override fun route(source: Int, target: Int): PathResult? {
        val s = space
        s.reset()
        s.set(source, 0, -1)
        s.heap.update(source, 0)
        while (!s.heap.isEmpty()) {
            val u = s.heap.popMin()
            s.settled++
            if (u == target) return PathResult(s.dist(u), s.pathTo(u), s.settled)
            val du = s.dist(u)
            for (e in graph.edgeStart(u) until graph.edgeEnd(u)) {
                val v = graph.target(e)
                val nd = du + graph.weight(e)
                if (nd < s.dist(v)) {
                    s.set(v, nd, u)
                    s.heap.update(v, nd)
                }
            }
        }
        return null
    }
}

/**
 * A* = Dijkstra ordered by g(v) + h(v), where h estimates the remaining cost.
 * With h(v) = straightLine(v, target) / vMax the estimate never overshoots (admissible)
 * and the search becomes an "ellipse" pointing to the target instead of a ball.
 *
 * Catch for car routing with *time* weights: vMax is the motorway speed, so h is weak
 * in cities (it assumes you could do 130 km/h through Brno) – the speed-up is modest.
 * That's exactly why production routers moved on to preprocessing techniques (CH, CRP).
 */
class AStar(private val graph: RoadGraph) : ShortestPathAlgorithm {
    override val name = "A*"
    private val space = SearchSpace(graph.nodeCount)
    private val msPerMeter = 1000.0 / graph.maxStraightLineSpeed()

    override fun route(source: Int, target: Int): PathResult? {
        val s = space
        s.reset()
        s.set(source, 0, -1)
        s.heap.update(source, heuristic(source, target))
        while (!s.heap.isEmpty()) {
            val u = s.heap.popMin()
            s.settled++
            if (u == target) return PathResult(s.dist(u), s.pathTo(u), s.settled)
            val du = s.dist(u)
            for (e in graph.edgeStart(u) until graph.edgeEnd(u)) {
                val v = graph.target(e)
                val nd = du + graph.weight(e)
                if (nd < s.dist(v)) {
                    s.set(v, nd, u)
                    s.heap.update(v, nd + heuristic(v, target))
                }
            }
        }
        return null
    }

    /** Floor of the true lower bound keeps it admissible after Int conversion. */
    private fun heuristic(v: Int, target: Int): Int = (graph.distance(v, target) * msPerMeter).toInt()
}

/**
 * Bidirectional Dijkstra: one search from the source on G, one from the target on reversed G.
 * Two balls of radius d/2 have together ~half the area of one ball of radius d.
 *
 * Stopping rule: once minKey(fwd) + minKey(bwd) >= best path found so far (μ), no better
 * meeting point can exist. (Stopping at the first node settled by both is a classic bug!)
 */
class BidirectionalDijkstra(private val graph: RoadGraph) : ShortestPathAlgorithm {
    override val name = "Bidirectional Dijkstra"
    private val reverse = graph.reversed()
    private val fwd = SearchSpace(graph.nodeCount)
    private val bwd = SearchSpace(graph.nodeCount)

    override fun route(source: Int, target: Int): PathResult? {
        fwd.reset()
        bwd.reset()
        fwd.set(source, 0, -1)
        fwd.heap.update(source, 0)
        bwd.set(target, 0, -1)
        bwd.heap.update(target, 0)
        var best = if (source == target) 0 else INF
        var meet = if (source == target) source else -1

        while (!fwd.heap.isEmpty() && !bwd.heap.isEmpty()) {
            if (fwd.heap.minKey().toLong() + bwd.heap.minKey() >= best) break
            // Rozšiřujeme menší frontu – vyvažuje práci obou směrů.
            val forward = fwd.heap.size <= bwd.heap.size
            val space = if (forward) fwd else bwd
            val other = if (forward) bwd else fwd
            val g = if (forward) graph else reverse
            val u = space.heap.popMin()
            space.settled++
            val du = space.dist(u)
            for (e in g.edgeStart(u) until g.edgeEnd(u)) {
                val v = g.target(e)
                val nd = du + g.weight(e)
                if (nd < space.dist(v)) {
                    space.set(v, nd, u)
                    space.heap.update(v, nd)
                }
                val dOther = other.dist(v)
                if (dOther != INF && space.dist(v).toLong() + dOther < best) {
                    best = space.dist(v) + dOther
                    meet = v
                }
            }
        }
        if (meet == -1) return null
        val forwardPart = fwd.pathTo(meet)
        val backwardPart = bwd.pathTo(meet).reversedArray() // meet … target
        val nodes = forwardPart + backwardPart.copyOfRange(1, backwardPart.size)
        return PathResult(best, nodes, fwd.settled + bwd.settled)
    }
}
