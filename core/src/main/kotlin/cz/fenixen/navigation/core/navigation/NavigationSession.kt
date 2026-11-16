package cz.fenixen.navigation.core.navigation

import cz.fenixen.navigation.core.model.LocationFix
import cz.fenixen.navigation.core.model.Maneuver
import cz.fenixen.navigation.core.model.Place
import cz.fenixen.navigation.core.model.Route
import cz.fenixen.navigation.core.model.RouteOptions
import cz.fenixen.navigation.core.model.RouteRequest
import cz.fenixen.navigation.core.routing.RoutingException
import cz.fenixen.navigation.core.routing.RoutingProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface NavigationState {
    data object Idle : NavigationState

    data class Planning(val destination: Place, val autoStart: Boolean) : NavigationState

    data class Preview(
        val destination: Place,
        val routes: List<Route>,
        val selectedIndex: Int = 0,
    ) : NavigationState {
        val selected: Route get() = routes[selectedIndex]
    }

    data class Navigating(
        val destination: Place,
        val route: Route,
        val progress: RouteProgress? = null,
        val speedMps: Double? = null,
        val isSpeeding: Boolean = false,
        val isRerouting: Boolean = false,
        /** Last reroute attempt failed (offline?) – we keep guiding on the old route. */
        val rerouteFailed: Boolean = false,
    ) : NavigationState {
        val upcoming: Maneuver? get() = progress?.let { route.maneuvers[it.upcomingManeuverIndex] }

        /** Maneuver after the upcoming one – shown as "Potom ..." when it comes quickly. */
        val following: Maneuver? get() = progress?.let { route.maneuvers.getOrNull(it.upcomingManeuverIndex + 1) }
    }

    data class Arrived(val destination: Place) : NavigationState

    data class Failed(val destination: Place?, val reason: FailureReason, val message: String) : NavigationState
}

enum class FailureReason { NO_LOCATION, NO_ROUTE, NETWORK, SERVER }

sealed interface NavigationEvent {
    data class Speak(val text: String) : NavigationEvent
    data object NavigationStarted : NavigationEvent
    data object RerouteStarted : NavigationEvent
    data object Rerouted : NavigationEvent
    data object Arrived : NavigationEvent
    data object NavigationEnded : NavigationEvent
    data class SpeedLimitExceeded(val limitKph: Int) : NavigationEvent
}

/**
 * Single source of truth for one navigation "session" – shared by the phone UI, the
 * Android Auto screens and the foreground service (they all just observe [state]).
 *
 * Threading: confine all calls to one thread (Android main). Network work runs in
 * coroutines launched in [scope] and never blocks that thread.
 */
class NavigationSession(
    private val routing: RoutingProvider,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val trackerConfig: TrackerConfig = TrackerConfig(),
    private val rerouteCooldownMs: Long = 4_000,
    private val speedingToleranceKph: Int = 5,
) {
    private val _state = MutableStateFlow<NavigationState>(NavigationState.Idle)
    val state: StateFlow<NavigationState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<NavigationEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<NavigationEvent> = _events.asSharedFlow()

    private val _lastFix = MutableStateFlow<LocationFix?>(null)
    val lastFix: StateFlow<LocationFix?> = _lastFix.asStateFlow()

    /** Avoid tolls / highways / ferries – applied to the next routing request. */
    var routeOptions: RouteOptions = RouteOptions()

    private var tracker: RouteProgressTracker? = null
    private var planner: AnnouncementPlanner? = null
    private var routingJob: Job? = null
    private var enrichJob: Job? = null
    private var lastRerouteAt = Long.MIN_VALUE / 2
    /** Bumped for every new active route so stale async results can be detected. */
    private var routeGeneration = 0

    /** Compute routes to [destination] and show them in preview (or start right away). */
    fun planRoute(destination: Place, autoStart: Boolean = false) {
        val origin = _lastFix.value
        if (origin == null) {
            _state.value = NavigationState.Failed(destination, FailureReason.NO_LOCATION, "Čekám na GPS polohu…")
            return
        }
        routingJob?.cancel()
        _state.value = NavigationState.Planning(destination, autoStart)
        routingJob = scope.launch {
            try {
                val routes = routing.route(RouteRequest(origin.point, destination.point, origin.movingBearing(), routeOptions))
                if (routes.isEmpty()) {
                    _state.value = NavigationState.Failed(destination, FailureReason.NO_ROUTE, "Trasu se nepodařilo najít.")
                } else if (autoStart) {
                    beginNavigation(destination, routes.first())
                } else {
                    _state.value = NavigationState.Preview(destination, routes)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: RoutingException) {
                _state.value = NavigationState.Failed(destination, e.kind.toReason(), e.kind.toMessage())
            }
        }
    }

    fun selectRoute(index: Int) {
        val preview = _state.value as? NavigationState.Preview ?: return
        if (index in preview.routes.indices) _state.value = preview.copy(selectedIndex = index)
    }

    fun startNavigation() {
        val preview = _state.value as? NavigationState.Preview ?: return
        beginNavigation(preview.destination, preview.selected)
    }

    /** Retry after a failure (e.g. network came back). */
    fun retry() {
        val failed = _state.value as? NavigationState.Failed ?: return
        failed.destination?.let { planRoute(it) } ?: run { _state.value = NavigationState.Idle }
    }

    /** Back to idle (cancel preview, end navigation, dismiss arrival). */
    fun stop() {
        val wasNavigating = _state.value is NavigationState.Navigating
        routingJob?.cancel()
        enrichJob?.cancel()
        tracker = null
        planner = null
        _state.value = NavigationState.Idle
        if (wasNavigating) _events.tryEmit(NavigationEvent.NavigationEnded)
    }

    fun onLocation(fix: LocationFix) {
        _lastFix.value = fix
        val nav = _state.value as? NavigationState.Navigating ?: return
        val tracker = tracker ?: return
        val planner = planner ?: return

        val result = tracker.update(fix)
        val limit = result.progress.speedLimitKph
        val speedKph = fix.speedMps?.times(3.6)
        val speeding = limit != null && speedKph != null && speedKph > limit + speedingToleranceKph
        val updated = nav.copy(progress = result.progress, speedMps = fix.speedMps, isSpeeding = speeding)
        _state.value = updated
        if (speeding && !nav.isSpeeding) _events.tryEmit(NavigationEvent.SpeedLimitExceeded(limit))

        when (result.status) {
            TrackingStatus.ON_ROUTE -> planner.next(result.progress, fix.speedMps)?.let(::speak)
            TrackingStatus.ARRIVED -> {
                // Pokud jsme „cílové“ hlášení ještě neřekli (rychlý dojezd), řekneme ho teď.
                planner.next(result.progress, fix.speedMps)?.let(::speak)
                arrive(updated.destination)
            }
            TrackingStatus.OFF_ROUTE -> reroute(updated, fix)
        }
    }

    private fun beginNavigation(destination: Place, route: Route) {
        routingJob?.cancel()
        activateRoute(route)
        _state.value = NavigationState.Navigating(destination, route)
        _events.tryEmit(NavigationEvent.NavigationStarted)
        _lastFix.value?.let(::onLocation)
    }

    private fun activateRoute(route: Route) {
        routeGeneration++
        tracker = RouteProgressTracker(route, trackerConfig)
        planner = AnnouncementPlanner(route)
        enrichSpeedLimits(route, routeGeneration)
    }

    private fun reroute(nav: NavigationState.Navigating, fix: LocationFix) {
        if (nav.isRerouting) return
        val now = clock()
        if (now - lastRerouteAt < rerouteCooldownMs) return
        lastRerouteAt = now
        _state.value = nav.copy(isRerouting = true)
        _events.tryEmit(NavigationEvent.RerouteStarted)
        routingJob = scope.launch {
            try {
                val request = RouteRequest(
                    origin = fix.point,
                    destination = nav.destination.point,
                    originBearing = fix.movingBearing(),
                    options = routeOptions.copy(alternatives = 0),
                )
                val newRoute = routing.route(request).firstOrNull()
                    ?: throw RoutingException(RoutingException.Kind.NO_ROUTE, "No route")
                val current = _state.value as? NavigationState.Navigating ?: return@launch
                activateRoute(newRoute)
                _state.value = current.copy(route = newRoute, progress = null, isRerouting = false, rerouteFailed = false)
                _events.tryEmit(NavigationEvent.Rerouted)
                _lastFix.value?.let(::onLocation)
            } catch (e: CancellationException) {
                throw e
            } catch (e: RoutingException) {
                // Offline apod.: vedeme dál po staré trase, další pokus po cooldownu.
                val current = _state.value as? NavigationState.Navigating ?: return@launch
                _state.value = current.copy(isRerouting = false, rerouteFailed = true)
            }
        }
    }

    private fun enrichSpeedLimits(route: Route, generation: Int) {
        enrichJob?.cancel()
        enrichJob = scope.launch {
            val spans = try {
                routing.speedLimits(route)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emptyList() // limity jsou bonus – jejich výpadek nesmí shodit navigaci
            }
            if (spans.isEmpty() || generation != routeGeneration) return@launch
            val enriched = route.copy(speedLimits = spans)
            tracker?.replaceRouteData(enriched)
            val current = _state.value as? NavigationState.Navigating ?: return@launch
            _state.value = current.copy(
                route = enriched,
                progress = current.progress?.copy(speedLimitKph = enriched.speedLimitAt(current.progress.distanceAlong)),
            )
        }
    }

    private fun arrive(destination: Place) {
        enrichJob?.cancel()
        tracker = null
        planner = null
        _state.value = NavigationState.Arrived(destination)
        _events.tryEmit(NavigationEvent.Arrived)
    }

    private fun speak(text: String) {
        _events.tryEmit(NavigationEvent.Speak(text))
    }

    private fun LocationFix.movingBearing(): Double? =
        bearingDeg?.takeIf { (speedMps ?: 0.0) >= trackerConfig.minSpeedForHeadingMps }

    private fun RoutingException.Kind.toReason() = when (this) {
        RoutingException.Kind.NETWORK -> FailureReason.NETWORK
        RoutingException.Kind.NO_ROUTE -> FailureReason.NO_ROUTE
        RoutingException.Kind.SERVER -> FailureReason.SERVER
    }

    private fun RoutingException.Kind.toMessage() = when (this) {
        RoutingException.Kind.NETWORK -> "Nelze se spojit se serverem tras. Zkontroluj připojení."
        RoutingException.Kind.NO_ROUTE -> "Do tohoto cíle nevede žádná trasa."
        RoutingException.Kind.SERVER -> "Server tras vrátil chybu."
    }
}
