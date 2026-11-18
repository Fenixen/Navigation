package cz.fenixen.navigation.core.traffic

import cz.fenixen.navigation.core.model.IncidentCategory

/** Czech names of incident categories – for the HUD and for speech. */
object IncidentTexts {
    fun label(c: IncidentCategory): String = when (c) {
        IncidentCategory.ACCIDENT -> "Nehoda"
        IncidentCategory.JAM -> "Kolona"
        IncidentCategory.ROAD_WORKS -> "Práce na silnici"
        IncidentCategory.LANE_CLOSED -> "Uzavřený pruh"
        IncidentCategory.ROAD_CLOSED -> "Uzavřená silnice"
        IncidentCategory.DANGER -> "Nebezpečí"
        IncidentCategory.WEATHER -> "Počasí"
        IncidentCategory.BROKEN_DOWN_VEHICLE -> "Porouchané vozidlo"
        IncidentCategory.OTHER -> "Dopravní omezení"
    }

    /** Lower-case form used after "Za 2 kilometry …". */
    fun spoken(c: IncidentCategory): String = when (c) {
        IncidentCategory.ACCIDENT -> "je nehoda"
        IncidentCategory.JAM -> "je kolona"
        IncidentCategory.ROAD_WORKS -> "jsou práce na silnici"
        IncidentCategory.LANE_CLOSED -> "je uzavřený pruh"
        IncidentCategory.ROAD_CLOSED -> "je uzavřená silnice"
        IncidentCategory.DANGER -> "je nebezpečí na silnici"
        IncidentCategory.WEATHER -> "je zhoršené počasí"
        IncidentCategory.BROKEN_DOWN_VEHICLE -> "stojí porouchané vozidlo"
        IncidentCategory.OTHER -> "je dopravní omezení"
    }
}
