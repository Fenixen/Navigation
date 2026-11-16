package cz.fenixen.navigation.core

import cz.fenixen.navigation.core.model.Route
import cz.fenixen.navigation.core.routing.ValhallaParser

/** Real API responses captured from Valhalla/Photon (OSM data © OpenStreetMap contributors, ODbL). */
object Fixtures {
    fun text(name: String): String =
        requireNotNull(javaClass.getResource("/fixtures/$name")) { "Missing fixture $name" }.readText()

    fun route(name: String, index: Int = 0): Route = ValhallaParser.parseRouteResponse(text(name))[index]
}
