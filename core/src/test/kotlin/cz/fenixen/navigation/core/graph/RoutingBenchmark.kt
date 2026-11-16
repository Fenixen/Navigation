package cz.fenixen.navigation.core.graph

import java.util.Locale
import kotlin.random.Random
import kotlin.system.measureNanoTime

/**
 * Run with: ./gradlew :core:routingBenchmark
 *
 * Not a JMH-grade benchmark (no forking, simple warm-up), but good enough to see
 * orders of magnitude – which is the point of the comparison.
 */
fun main(args: Array<String>) {
    val size = args.firstOrNull()?.toIntOrNull() ?: 300
    val queries = 300
    println("Generuji silniční síť ${size}×$size …")
    val graph = SyntheticRoadNetwork.generate(width = size, height = size, seed = 2026)
    println("Uzlů: ${graph.nodeCount}, hran: ${graph.edgeCount}")

    lateinit var ch: ContractionHierarchy
    val prepMs = measureNanoTime { ch = ContractionHierarchy.build(graph) } / 1e6
    println(
        String.format(
            Locale.ROOT,
            "CH předzpracování: %.0f ms, zkratek: %d (%.0f %% hran navíc)",
            prepMs,
            ch.shortcutCount,
            100.0 * ch.shortcutCount / graph.edgeCount,
        ),
    )

    val algorithms = listOf(
        Dijkstra(graph),
        AStar(graph),
        BidirectionalDijkstra(graph),
        ch.newQuery(stallOnDemand = false),
        ch.newQuery(stallOnDemand = true),
    )
    val rnd = Random(99)
    val pairs = List(queries) { rnd.nextInt(graph.nodeCount) to rnd.nextInt(graph.nodeCount) }

    // Warm-up (JIT)
    for (alg in algorithms) pairs.take(50).forEach { (s, t) -> alg.route(s, t) }

    println()
    println(String.format(Locale.ROOT, "%-26s %14s %14s %10s", "Algoritmus", "Ø usazených", "Ø čas dotazu", "zrychlení"))
    var baselineUs = 0.0
    for (alg in algorithms) {
        var settled = 0L
        var checksum = 0L
        val ns = measureNanoTime {
            for ((s, t) in pairs) {
                val r = alg.route(s, t)
                settled += r?.settledNodes ?: 0
                checksum += r?.cost ?: 0
            }
        }
        val avgUs = ns / 1e3 / queries
        if (baselineUs == 0.0) baselineUs = avgUs
        println(
            String.format(
                Locale.ROOT,
                "%-26s %14d %11.1f µs %9.0f×   (checksum %d)",
                alg.name,
                settled / queries,
                avgUs,
                baselineUs / avgUs,
                checksum,
            ),
        )
    }
}
