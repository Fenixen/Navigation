package cz.fenixen.navigation.core.routing

import cz.fenixen.navigation.core.model.Route
import cz.fenixen.navigation.core.model.RouteRequest
import cz.fenixen.navigation.core.model.SpeedLimitSpan

/**
 * Port (in the hexagonal-architecture sense) for anything that can compute routes:
 * an online Valhalla server, a self-hosted one on the Raspberry Pi, or the on-device
 * Contraction Hierarchies engine in [cz.fenixen.navigation.core.graph].
 */
interface RoutingProvider {
    /** Returns the best route first, followed by alternatives. Throws [RoutingException]. */
    suspend fun route(request: RouteRequest): List<Route>

    /** Optional enrichment – speed limits along an already computed route. */
    suspend fun speedLimits(route: Route): List<SpeedLimitSpan> = emptyList()
}

class RoutingException(
    val kind: Kind,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {
    enum class Kind {
        /** No connectivity / timeout – worth retrying later. */
        NETWORK,
        /** The router answered but there is no path (e.g. destination on an island). */
        NO_ROUTE,
        /** Unexpected server response. */
        SERVER,
    }
}
