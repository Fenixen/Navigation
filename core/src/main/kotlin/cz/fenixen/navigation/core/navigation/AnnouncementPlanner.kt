package cz.fenixen.navigation.core.navigation

import cz.fenixen.navigation.core.format.CzechFormat
import cz.fenixen.navigation.core.format.CzechText
import cz.fenixen.navigation.core.model.Route

/**
 * Decides *when* to say *what*. Timing is speed-based (seconds before the maneuver),
 * not fixed distances – 300 m means 22 s in the city but only 8 s on the D1.
 *
 * Stages per maneuver: FAR → NEAR → NOW. Each stage is spoken at most once and a later
 * stage suppresses earlier ones (no "in 800 m" right after we already said "turn now").
 */
class AnnouncementPlanner(
    private val route: Route,
    private val config: AnnouncementConfig = AnnouncementConfig(),
) {
    enum class Stage { NONE, POST, FAR, NEAR, NOW }

    private val spoken = HashMap<Int, Stage>()
    private var lastUpcoming = -1

    /** Returns the sentence to speak for this progress update, or null. */
    fun next(progress: RouteProgress, speedMps: Double?): String? {
        val index = progress.upcomingManeuverIndex
        val maneuver = route.maneuvers[index]
        val v = (speedMps ?: 0.0).coerceAtLeast(config.minAssumedSpeedMps)
        val d = progress.distanceToUpcoming

        // Právě jsme projeli manévr → případně „Pokračujte 12 kilometrů“.
        if (index != lastUpcoming) {
            val first = lastUpcoming == -1
            lastUpcoming = index
            if (!first && d >= config.postAnnouncementMinM && spoken[index] == null) {
                spoken[index] = Stage.POST
                return "Pokračujte ${CzechFormat.spokenDistance(d)}."
            }
        }

        val nowDist = (v * config.nowSeconds).coerceIn(config.nowMinM, config.nowMaxM)
        val nearDist = (v * config.nearSeconds).coerceIn(config.nearMinM, config.nearMaxM)
        val farDist = (v * config.farSeconds).coerceIn(config.farMinM, config.farMaxM)
        val previous = route.maneuvers[(index - 1).coerceAtLeast(0)]
        val legLength = previous.lengthMeters

        val due = when {
            d <= nowDist -> Stage.NOW
            d <= nearDist -> Stage.NEAR
            // Daleké upozornění jen když úsek je dost dlouhý (dálnice), ve městě by to bylo otravné.
            d <= farDist && legLength > farDist + config.farMinLegExtraM -> Stage.FAR
            else -> Stage.NONE
        }
        if (due == Stage.NONE) return null
        val already = spoken[index] ?: Stage.NONE
        if (due.ordinal <= already.ordinal) return null
        // NEAR těsně před NOW nemá smysl – vynech ho, pokud by mezi nimi byly méně než ~4 s.
        if (due == Stage.NEAR && d - nowDist < v * config.minGapSeconds) return null

        spoken[index] = due
        return when (due) {
            Stage.NOW -> maneuver.verbalPre ?: maneuver.instruction
            Stage.NEAR, Stage.FAR -> {
                val core = maneuver.verbalAlert ?: maneuver.verbalPre ?: maneuver.instruction
                "Za ${CzechFormat.spokenDistance(d)} ${CzechText.decapitalize(core)}"
            }
            else -> null
        }
    }
}

data class AnnouncementConfig(
    /** Below ~30 km/h use this speed for timing so we still warn early enough when slowing down. */
    val minAssumedSpeedMps: Double = 8.0,
    val nowSeconds: Double = 5.0,
    val nowMinM: Double = 25.0,
    val nowMaxM: Double = 160.0,
    val nearSeconds: Double = 18.0,
    val nearMinM: Double = 150.0,
    val nearMaxM: Double = 800.0,
    val farSeconds: Double = 60.0,
    val farMinM: Double = 800.0,
    val farMaxM: Double = 2_500.0,
    val farMinLegExtraM: Double = 300.0,
    val minGapSeconds: Double = 4.0,
    /** Say "continue for X km" only after a maneuver followed by a long stretch. */
    val postAnnouncementMinM: Double = 3_000.0,
)
