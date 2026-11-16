package cz.fenixen.navigation.core.graph

/**
 * Contraction Hierarchies (Geisberger et al., 2008) – the technique behind OSRM and GraphHopper.
 *
 * Intuition (F1 analogy): instead of planning every corner of every lap, the team knows the
 * "important" sections. Preprocessing ranks nodes by importance (side street < arterial < motorway)
 * and *contracts* them one by one from least important: removing node v, for each pair
 * u → v → w we add a **shortcut** u → w if u-v-w was the only shortest way (no "witness" path).
 *
 * Query: bidirectional Dijkstra that only goes **upwards** in rank from both ends. Both searches
 * climb to the motorway level quickly and meet there → a few hundred settled nodes instead of
 * millions, i.e. microseconds instead of seconds. Shortcuts are unpacked back into real edges.
 *
 * Price: minutes of preprocessing per country and no cheap weight changes (that's why traffic-aware
 * systems use CRP / Customizable CH, where the metric can be re-applied in seconds).
 */
class ContractionHierarchy private constructor(
    private val nodeCount: Int,
    /** rank[v] = contraction order (higher = more important). */
    val rank: IntArray,
    // Všechny hrany (původní + zkratky) – sdílené pole, CSR indexy ukazují sem.
    private val edgeFrom: IntArray,
    private val edgeTo: IntArray,
    private val edgeWeight: IntArray,
    /** For shortcuts: the two child edge ids; -1 for original edges. */
    private val edgeChild1: IntArray,
    private val edgeChild2: IntArray,
    /** Upward graph: for node a, edges a→b with rank[b] > rank[a]. */
    private val upFirst: IntArray,
    private val upEdges: IntArray,
    /** Backward-upward graph: for node b, edges a→b with rank[a] > rank[b]. */
    private val downFirst: IntArray,
    private val downEdges: IntArray,
    val shortcutCount: Int,
) {
    val originalEdgeCount: Int get() = edgeFrom.size - shortcutCount

    /** Creates a query object (one per thread – it owns mutable search state). */
    fun newQuery(stallOnDemand: Boolean = true): ShortestPathAlgorithm = Query(stallOnDemand)

    private inner class Query(private val stallOnDemand: Boolean) : ShortestPathAlgorithm {
        override val name = if (stallOnDemand) "Contraction Hierarchies" else "CH (no stalling)"
        private val fwd = SearchSpace(nodeCount)
        private val bwd = SearchSpace(nodeCount)

        override fun route(source: Int, target: Int): PathResult? {
            fwd.reset()
            bwd.reset()
            fwd.set(source, 0, -1)
            fwd.heap.update(source, 0)
            bwd.set(target, 0, -1)
            bwd.heap.update(target, 0)
            var best = if (source == target) 0 else INF
            var meet = if (source == target) source else -1

            // V CH nelze použít kritérium minF + minB >= μ (prostory nejsou "koule"),
            // každý směr se zastaví, až když jeho minimum přesáhne μ.
            while (true) {
                val fwdActive = !fwd.heap.isEmpty() && fwd.heap.minKey() < best
                val bwdActive = !bwd.heap.isEmpty() && bwd.heap.minKey() < best
                if (!fwdActive && !bwdActive) break
                val forward = fwdActive && (!bwdActive || fwd.heap.minKey() <= bwd.heap.minKey())
                val space = if (forward) fwd else bwd
                val other = if (forward) bwd else fwd
                val first = if (forward) upFirst else downFirst
                val edges = if (forward) upEdges else downEdges
                val stallFirst = if (forward) downFirst else upFirst
                val stallEdges = if (forward) downEdges else upEdges

                val u = space.heap.popMin()
                space.settled++
                val du = space.dist(u)

                val dOther = other.dist(u)
                if (dOther != INF && du.toLong() + dOther < best) {
                    best = du + dOther
                    meet = u
                }

                if (stallOnDemand && isStalled(u, du, space, stallFirst, stallEdges, forward)) continue

                for (i in first[u] until first[u + 1]) {
                    val e = edges[i]
                    val v = if (forward) edgeTo[e] else edgeFrom[e]
                    val nd = du + edgeWeight[e]
                    if (nd < space.dist(v)) {
                        space.set(v, nd, u, e)
                        space.heap.update(v, nd)
                    }
                }
            }
            if (meet == -1) return null
            return PathResult(best, unpackPath(meet), fwd.settled + bwd.settled)
        }

        /**
         * Stall-on-demand: if a higher-ranked node x reaches u cheaper via an edge pointing
         * *down* into u, then u's tentative distance isn't optimal and expanding it is useless.
         */
        private fun isStalled(
            u: Int,
            du: Int,
            space: SearchSpace,
            first: IntArray,
            edges: IntArray,
            forward: Boolean,
        ): Boolean {
            for (i in first[u] until first[u + 1]) {
                val e = edges[i]
                // Forward: hrana x→u s rank[x] > rank[u] (z down grafu). Backward: hrana u→x.
                val x = if (forward) edgeFrom[e] else edgeTo[e]
                val dx = space.dist(x)
                if (dx != INF && dx.toLong() + edgeWeight[e] < du) return true
            }
            return false
        }

        private fun unpackPath(meet: Int): IntArray {
            // Hrany od zdroje k místu setkání (sestupně přes parent pointery), pak od setkání k cíli.
            val forwardEdges = ArrayList<Int>()
            var v = meet
            while (true) {
                val e = fwd.parentEdge(v)
                if (e == -1) break
                forwardEdges += e
                v = fwd.parent(v)
            }
            forwardEdges.reverse()
            val backwardEdges = ArrayList<Int>()
            v = meet
            while (true) {
                val e = bwd.parentEdge(v)
                if (e == -1) break
                backwardEdges += e
                v = bwd.parent(v)
            }
            val source = if (forwardEdges.isEmpty()) meet else edgeFrom[forwardEdges.first()]
            val nodes = ArrayList<Int>()
            nodes += source
            for (e in forwardEdges + backwardEdges) unpackEdge(e, nodes)
            return nodes.toIntArray()
        }

        /** Iterative (stack-based) shortcut unpacking – recursion could overflow on long routes. */
        private fun unpackEdge(edge: Int, out: MutableList<Int>) {
            val stack = ArrayDeque<Int>()
            stack.addLast(edge)
            while (stack.isNotEmpty()) {
                val e = stack.removeLast()
                if (edgeChild1[e] == -1) {
                    out += edgeTo[e]
                } else {
                    stack.addLast(edgeChild2[e]) // druhá půlka až po první
                    stack.addLast(edgeChild1[e])
                }
            }
        }
    }

    /** Tunables of preprocessing. Limits trade a few extra shortcuts for much faster builds. */
    data class Params(
        val witnessSettleLimitPriority: Int = 60,
        val witnessSettleLimitContract: Int = 600,
    )

    companion object {
        fun build(graph: RoadGraph, params: Params = Params()): ContractionHierarchy =
            Builder(graph, params).build()
    }

    /** Preprocessing. Mutable, single use. */
    private class Builder(private val graph: RoadGraph, private val params: Params) {
        private val n = graph.nodeCount
        private val from = IntList()
        private val to = IntList()
        private val weight = IntList()
        private val child1 = IntList()
        private val child2 = IntList()
        private val outEdges = Array(n) { IntList(4) }
        private val inEdges = Array(n) { IntList(4) }
        /** (from, to) → edge id; keeps only the cheapest parallel edge. */
        private val edgeIndex = HashMap<Long, Int>()
        private val contracted = BooleanArray(n)
        private val deletedNeighbors = IntArray(n)
        private val witness = SearchSpace(n)

        fun build(): ContractionHierarchy {
            for (v in 0 until n) {
                graph.forEachOutEdge(v) { t, w -> if (t != v) addOrImprove(v, t, w, -1, -1) }
            }

            val queue = IndexedMinHeap(n)
            for (v in 0 until n) queue.update(v, priority(v))
            val rank = IntArray(n)
            var order = 0
            while (!queue.isEmpty()) {
                // Lazy update: priorita se mohla od vložení zhoršit → přepočítej a případně vrať zpět.
                val v = queue.popMin()
                val p = priority(v)
                if (!queue.isEmpty() && p > queue.minKey()) {
                    queue.update(v, p)
                    continue
                }
                contract(v)
                rank[v] = order++
                for (nb in neighbors(v)) {
                    deletedNeighbors[nb]++
                    queue.update(nb, priority(nb))
                }
            }
            return assemble(rank)
        }

        private fun neighbors(v: Int): Set<Int> {
            val out = HashSet<Int>()
            inEdges[v].forEach { e -> val u = from[e]; if (!contracted[u]) out += u }
            outEdges[v].forEach { e -> val w = to[e]; if (!contracted[w]) out += w }
            return out
        }

        /** Edge difference + deleted neighbours: prefer nodes whose removal adds few shortcuts. */
        private fun priority(v: Int): Int {
            var degree = 0
            inEdges[v].forEach { e -> if (!contracted[from[e]]) degree++ }
            outEdges[v].forEach { e -> if (!contracted[to[e]]) degree++ }
            val needed = shortcutsFor(v, params.witnessSettleLimitPriority, apply = false)
            return 2 * (needed - degree) + deletedNeighbors[v]
        }

        private fun contract(v: Int) {
            shortcutsFor(v, params.witnessSettleLimitContract, apply = true)
            contracted[v] = true
        }

        /** Counts (and optionally inserts) shortcuts needed when removing [v]. */
        private fun shortcutsFor(v: Int, settleLimit: Int, apply: Boolean): Int {
            var count = 0
            var maxOut = 0
            outEdges[v].forEach { e -> if (!contracted[to[e]] && weight[e] > maxOut) maxOut = weight[e] }
            inEdges[v].forEach inLoop@{ eIn ->
                val u = from[eIn]
                if (contracted[u]) return@inLoop
                val wIn = weight[eIn]
                witnessSearch(u, v, wIn + maxOut, settleLimit)
                outEdges[v].forEach outLoop@{ eOut ->
                    val w = to[eOut]
                    if (contracted[w] || w == u) return@outLoop
                    val viaV = wIn + weight[eOut]
                    if (witness.dist(w) > viaV) {
                        count++
                        if (apply) {
                            addOrImprove(u, w, viaV, eIn, eOut)
                        }
                    }
                }
            }
            return count
        }

        /** Local Dijkstra from [source] avoiding [skip] and contracted nodes, bounded by cost and size. */
        private fun witnessSearch(source: Int, skip: Int, maxCost: Int, settleLimit: Int) {
            val s = witness
            s.reset()
            s.set(source, 0, -1)
            s.heap.update(source, 0)
            while (!s.heap.isEmpty()) {
                if (s.heap.minKey() > maxCost || s.settled >= settleLimit) break
                val u = s.heap.popMin()
                s.settled++
                val du = s.dist(u)
                outEdges[u].forEach { e ->
                    val t = to[e]
                    if (t != skip && !contracted[t]) {
                        val nd = du + weight[e]
                        if (nd < s.dist(t)) {
                            s.set(t, nd, u)
                            s.heap.update(t, nd)
                        }
                    }
                }
            }
        }

        private fun addOrImprove(a: Int, b: Int, w: Int, c1: Int, c2: Int) {
            val key = (a.toLong() shl 32) or (b.toLong() and 0xffffffffL)
            val existing = edgeIndex[key]
            if (existing != null) {
                if (w < weight[existing]) {
                    weight[existing] = w
                    child1[existing] = c1
                    child2[existing] = c2
                }
                return
            }
            val id = from.size
            from.add(a); to.add(b); weight.add(w); child1.add(c1); child2.add(c2)
            outEdges[a].add(id)
            inEdges[b].add(id)
            edgeIndex[key] = id
        }

        private fun assemble(rank: IntArray): ContractionHierarchy {
            val m = from.size
            val upCount = IntArray(n + 1)
            val downCount = IntArray(n + 1)
            for (e in 0 until m) {
                val a = from[e]
                val b = to[e]
                if (rank[a] < rank[b]) upCount[a + 1]++ else downCount[b + 1]++
            }
            for (v in 0 until n) {
                upCount[v + 1] += upCount[v]
                downCount[v + 1] += downCount[v]
            }
            val upEdges = IntArray(upCount[n])
            val downEdges = IntArray(downCount[n])
            val upCursor = upCount.copyOf(n)
            val downCursor = downCount.copyOf(n)
            for (e in 0 until m) {
                val a = from[e]
                val b = to[e]
                if (rank[a] < rank[b]) upEdges[upCursor[a]++] = e else downEdges[downCursor[b]++] = e
            }
            // Počet zkratek = hrany s potomky (vylepšené původní hrany se počítají také jako zkratky).
            var shortcutEdges = 0
            for (e in 0 until m) if (child1[e] != -1) shortcutEdges++
            return ContractionHierarchy(
                nodeCount = n,
                rank = rank,
                edgeFrom = from.toArray(),
                edgeTo = to.toArray(),
                edgeWeight = weight.toArray(),
                edgeChild1 = child1.toArray(),
                edgeChild2 = child2.toArray(),
                upFirst = upCount,
                upEdges = upEdges,
                downFirst = downCount,
                downEdges = downEdges,
                shortcutCount = shortcutEdges,
            )
        }
    }
}

/** Minimal growable IntArray (avoids boxing of ArrayList<Int>). */
internal class IntList(initialCapacity: Int = 16) {
    private var data = IntArray(initialCapacity.coerceAtLeast(1))
    var size = 0
        private set

    fun add(v: Int) {
        if (size == data.size) data = data.copyOf(size * 2)
        data[size++] = v
    }

    operator fun get(i: Int): Int = data[i]

    operator fun set(i: Int, v: Int) {
        data[i] = v
    }

    inline fun forEach(action: (Int) -> Unit) {
        for (i in 0 until size) action(this[i])
    }

    fun toArray(): IntArray = data.copyOf(size)
}
