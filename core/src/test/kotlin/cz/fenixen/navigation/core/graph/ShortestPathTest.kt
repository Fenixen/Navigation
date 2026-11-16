package cz.fenixen.navigation.core.graph

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ShortestPathTest {

    /** Small hand-made graph where the fastest path is NOT the one with fewest edges. */
    private fun diamond(): RoadGraph {
        val b = RoadGraph.Builder()
        repeat(5) { b.addNode(49.0 + it * 0.001, 16.0) }
        b.addEdge(0, 1, 10_000) // 0 → 1 → 4 = 10 + 50 = 60 s
        b.addEdge(1, 4, 50_000)
        b.addEdge(0, 2, 5_000) // 0 → 2 → 3 → 4 = 5 + 5 + 5 = 15 s
        b.addEdge(2, 3, 5_000)
        b.addEdge(3, 4, 5_000)
        b.addEdge(4, 0, 1_000) // zpětná hrana (jednosměrky)
        return b.build()
    }

    private fun allAlgorithms(g: RoadGraph): List<ShortestPathAlgorithm> = listOf(
        Dijkstra(g),
        AStar(g),
        BidirectionalDijkstra(g),
        ContractionHierarchy.build(g).newQuery(stallOnDemand = true),
        ContractionHierarchy.build(g).newQuery(stallOnDemand = false),
    )

    @Test
    fun `all algorithms find the fastest path in the diamond`() {
        val g = diamond()
        for (alg in allAlgorithms(g)) {
            val r = assertNotNull(alg.route(0, 4), alg.name)
            assertEquals(15_000, r.cost, alg.name)
            assertEquals(listOf(0, 2, 3, 4), r.nodes.toList(), alg.name)
        }
    }

    @Test
    fun `respects one-way streets and reports unreachable targets`() {
        val b = RoadGraph.Builder()
        repeat(3) { b.addNode(49.0, 16.0 + it * 0.001) }
        b.addEdge(0, 1, 1_000)
        val g = b.build() // uzel 2 je izolovaný, 1 → 0 neexistuje
        for (alg in allAlgorithms(g)) {
            assertNull(alg.route(1, 0), "${alg.name}: one-way")
            assertNull(alg.route(0, 2), "${alg.name}: isolated")
            assertEquals(0, alg.route(2, 2)?.cost, "${alg.name}: source == target")
        }
    }

    @Test
    fun `all algorithms agree with Dijkstra on random directed graphs`() {
        for (seed in 1L..6L) {
            val g = SyntheticRoadNetwork.randomGraph(nodes = 400, edges = 1_600, seed = seed)
            assertAgreement(g, queries = 150, seed = seed)
        }
    }

    @Test
    fun `all algorithms agree with Dijkstra on a road-like network`() {
        val g = SyntheticRoadNetwork.generate(width = 40, height = 40, seed = 7)
        assertAgreement(g, queries = 200, seed = 7)
    }

    @Test
    fun `contraction hierarchy settles far fewer nodes than Dijkstra`() {
        val g = SyntheticRoadNetwork.generate(width = 60, height = 60, seed = 3)
        val dijkstra = Dijkstra(g)
        val ch = ContractionHierarchy.build(g).newQuery()
        val rnd = Random(11)
        var dSettled = 0L
        var chSettled = 0L
        repeat(100) {
            val s = rnd.nextInt(g.nodeCount)
            val t = rnd.nextInt(g.nodeCount)
            dSettled += dijkstra.route(s, t)?.settledNodes ?: 0
            chSettled += ch.route(s, t)?.settledNodes ?: 0
        }
        assertTrue(chSettled * 5 < dSettled, "CH settled $chSettled vs Dijkstra $dSettled")
    }

    private fun assertAgreement(g: RoadGraph, queries: Int, seed: Long) {
        val reference = Dijkstra(g)
        val others = allAlgorithms(g).drop(1)
        val rnd = Random(seed * 31)
        repeat(queries) {
            val s = rnd.nextInt(g.nodeCount)
            val t = rnd.nextInt(g.nodeCount)
            val expected = reference.route(s, t)
            for (alg in others) {
                val actual = alg.route(s, t)
                if (expected == null) {
                    assertNull(actual, "${alg.name} $s→$t should be unreachable")
                } else {
                    assertNotNull(actual, "${alg.name} $s→$t should be reachable")
                    assertEquals(expected.cost, actual.cost, "${alg.name} cost $s→$t")
                    assertValidPath(g, actual, s, t, alg.name)
                }
            }
        }
    }

    /** Path must start/end correctly, use existing edges and its edge costs must sum to the cost. */
    private fun assertValidPath(g: RoadGraph, r: PathResult, s: Int, t: Int, name: String) {
        assertEquals(s, r.nodes.first(), "$name start")
        assertEquals(t, r.nodes.last(), "$name end")
        var sum = 0L
        for (i in 0 until r.nodes.size - 1) {
            val u = r.nodes[i]
            val v = r.nodes[i + 1]
            var cheapest = Int.MAX_VALUE
            g.forEachOutEdge(u) { target, w -> if (target == v && w < cheapest) cheapest = w }
            assertTrue(cheapest != Int.MAX_VALUE, "$name: edge $u→$v does not exist")
            sum += cheapest
        }
        assertEquals(r.cost.toLong(), sum, "$name: path cost mismatch")
    }
}
