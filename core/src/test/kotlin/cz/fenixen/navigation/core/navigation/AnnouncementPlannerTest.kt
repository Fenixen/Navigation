package cz.fenixen.navigation.core.navigation

import cz.fenixen.navigation.core.Fixtures
import cz.fenixen.navigation.core.model.Route
import cz.fenixen.navigation.core.simulation.RouteSimulator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AnnouncementPlannerTest {

    private fun spokenDuringDrive(route: Route): List<String> {
        val tracker = RouteProgressTracker(route)
        val planner = AnnouncementPlanner(route)
        val sim = RouteSimulator(route)
        val out = mutableListOf<String>()
        var t = 0.0
        while (true) {
            val fix = sim.fixAt(t, 0) ?: break
            val result = tracker.update(fix)
            planner.next(result.progress, fix.speedMps)?.let { out += it }
            t += 1.0
        }
        sim.fixAt(sim.totalSeconds, 0)?.let { fix ->
            planner.next(tracker.update(fix).progress, fix.speedMps)?.let { out += it }
        }
        return out
    }

    @Test
    fun `highway drive gets early warning, final instruction and arrival`() {
        val route = Fixtures.route("valhalla_route_highway.json")
        val spoken = spokenDuringDrive(route)
        println(spoken.joinToString("\n"))

        // Výjezd 230 na D46: dlouhý úsek dálnice → dřívější upozornění na km i finální pokyn.
        assertTrue(
            spoken.any { it.startsWith("Za ") && it.contains("kilometr") && it.contains("výjezdu 230") },
            "early exit warning",
        )
        assertTrue(spoken.any { it.startsWith("Sjeďte na výjezdu 230") }, "final exit instruction")
        assertTrue(spoken.any { it.startsWith("Pokračujte") && it.contains("kilometr") }, "post-maneuver info")
        assertEquals("Dorazili jste do svého cíle.", spoken.last())
        // Nic se neopakuje hned za sebou (stejný text u dvou různých rozdvojení je v pořádku).
        assertTrue(spoken.zipWithNext().none { (a, b) -> a == b }, "repeated announcement")
    }

    @Test
    fun `every maneuver gets its final instruction exactly once in the city`() {
        val route = Fixtures.route("valhalla_route_brno.json")
        val spoken = spokenDuringDrive(route)
        println(spoken.joinToString("\n"))
        // Manévr pár metrů za předchozím (při 1 Hz GPS ho „přeskočíme“) pokrývá Valhallí
        // multi-cue předchozího pokynu („…Poté odbočte vpravo“), takže ho nevyžadujeme.
        val reachable = route.maneuvers.indices.drop(1).filter { route.maneuvers[it - 1].lengthMeters >= 30 }
        val finals = reachable.map { route.maneuvers[it].let { m -> m.verbalPre ?: m.instruction } }
        for (text in finals.distinct()) {
            assertEquals(finals.count { it == text }, spoken.count { it == text }, "'$text'")
        }
        // Ve městě žádná „daleká“ hlášení na kilometry.
        assertTrue(spoken.none { it.startsWith("Za") && it.contains("kilometr") })
    }
}
