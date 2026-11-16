package cz.fenixen.navigation.location

import cz.fenixen.navigation.core.model.Route
import cz.fenixen.navigation.core.navigation.NavigationSession
import cz.fenixen.navigation.core.navigation.NavigationState
import cz.fenixen.navigation.core.simulation.RouteSimulator
import cz.fenixen.navigation.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Decides *where* location fixes come from and feeds them into the [NavigationSession].
 *
 *  - Reference counted demand: phone UI, car screen and the foreground service each "acquire"
 *    it; GPS runs only while someone needs it (battery!).
 *  - Simulation: with "Simulace jízdy" enabled and a route active, fixes come from
 *    [RouteSimulator] instead of GPS – handy on the Android Auto Desktop Head Unit.
 */
class LocationCoordinator(
    private val scope: CoroutineScope,
    private val repository: LocationRepository,
    private val session: NavigationSession,
    settings: SettingsRepository,
) {
    private val demand = MutableStateFlow(0)
    private val permissionTick = MutableStateFlow(0)

    init {
        val activeRoute = session.state
            .map { (it as? NavigationState.Navigating)?.route }
            // Obohacení o rychlostní limity mění objekt Route, ale ne geometrii → simulace běží dál.
            .distinctUntilChanged { a, b -> a?.geometry === b?.geometry }
        val simulate = settings.current.map { it.simulateDriving }.distinctUntilChanged()
        val active = demand.map { it > 0 }.distinctUntilChanged()

        scope.launch {
            combine(active, simulate, activeRoute, permissionTick) { isActive, sim, route, _ ->
                Source(isActive, if (sim) route else null)
            }.collectLatest { source -> run(source) }
        }
    }

    private data class Source(val active: Boolean, val simulatedRoute: Route?)

    private suspend fun run(source: Source) {
        if (!source.active) return
        val route = source.simulatedRoute
        if (route != null) {
            RouteSimulator(route).fixes().collect(session::onLocation)
            return
        }
        if (!repository.hasPermission()) return
        repository.lastKnown()?.let { if (session.lastFix.value == null) session.onLocation(it) }
        repository.updates().collect(session::onLocation)
    }

    /** Returns a release function – call it when the consumer stops (onStop / onDestroy). */
    fun acquire(): () -> Unit {
        demand.update { it + 1 }
        var released = false
        return {
            if (!released) {
                released = true
                demand.update { (it - 1).coerceAtLeast(0) }
            }
        }
    }

    /** Call after the user granted location permission so updates start immediately. */
    fun onPermissionChanged() {
        permissionTick.update { it + 1 }
    }
}
