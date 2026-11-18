package cz.fenixen.navigation.core.navigation

import cz.fenixen.navigation.core.model.LocationFix
import cz.fenixen.navigation.core.model.Maneuver
import cz.fenixen.navigation.core.model.Place
import cz.fenixen.navigation.core.model.Route
import cz.fenixen.navigation.core.model.RouteOptions
import cz.fenixen.navigation.core.model.RouteRequest
import cz.fenixen.navigation.core.format.CzechFormat
import cz.fenixen.navigation.core.model.AvoidArea
import cz.fenixen.navigation.core.model.IncidentCategory
import cz.fenixen.navigation.core.model.TrafficLevel
import cz.fenixen.navigation.core.model.RouteIncident
import cz.fenixen.navigation.core.model.RouteTraffic
import cz.fenixen.navigation.core.model.TrafficIncident
import cz.fenixen.navigation.core.geo.Polyline
import cz.fenixen.navigation.core.traffic.IncidentTexts
import cz.fenixen.navigation.core.traffic.RouteMatching
import cz.fenixen.navigation.core.routing.RoutingException
import cz.fenixen.navigation.core.routing.RoutingProvider
import cz.fenixen.navigation.core.traffic.TrafficProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

sealed interface NavigationState {
    data object Idle : NavigationState

    data class Planning(val destination: Place, val autoStart: Boolean) : NavigationState

    data class Preview(
        val destination: Place,
        val routes: List<Route>,
        val selectedIndex: Int = 0,
        /** How many known closures the routes go around. */
        val avoidedClosures: Int = 0,
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
        /** Live incidents ahead on the route, nearest first. */
        val incidents: List<RouteIncident> = emptyList(),
        /** A traffic-aware faster route waiting for the driver's decision. */
        val fasterRoute: FasterRouteOffer? = null,
    ) : NavigationState {
        /** Nearest incident still ahead of the car. */
        val nextIncident: RouteIncident? get() = incidents.firstOrNull { it.distanceAlong >= (progress?.distanceAlong ?: 0.0) - 20 }
        val upcoming: Maneuver? get() = progress?.let { route.maneuvers[it.upcomingManeuverIndex] }

        /** Maneuver after the upcoming one – shown as "Potom ..." when it comes quickly. */
        val following: Maneuver? get() = progress?.let { route.maneuvers.getOrNull(it.upcomingManeuverIndex + 1) }
    }

    data class Arrived(val destination: Place) : NavigationState

    data class Failed(val destination: Place?, val reason: FailureReason, val message: String) : NavigationState
}

enum class FailureReason { NO_LOCATION, NO_ROUTE, NETWORK, SERVER }

data class FasterRouteOffer(val id: Int, val route: Route, val savingSeconds: Double, val createdAtMillis: Long)

sealed interface NavigationEvent {
    data class Speak(val text: String) : NavigationEvent
    data object NavigationStarted : NavigationEvent
    data object RerouteStarted : NavigationEvent
    data object Rerouted : NavigationEvent
    data object Arrived : NavigationEvent
    data object NavigationEnded : NavigationEvent
    data class SpeedLimitExceeded(val limitKph: Int) : NavigationEvent
    data class TrafficUpdated(val delaySeconds: Double) : NavigationEvent
    data class FasterRouteFound(val offer: FasterRouteOffer) : NavigationEvent
    data object ClosureAvoided : NavigationEvent
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
    /** Live traffic source; returns null when not configured (e.g. no API key). */
    private val traffic: () -> TrafficProvider? = { null },
    private val trafficRefreshMs: Long = 180_000,
) {
    private val _state = MutableStateFlow<NavigationState>(NavigationState.Idle)
    val state: StateFlow<NavigationState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<NavigationEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<NavigationEvent> = _events.asSharedFlow()

    private val _lastFix = MutableStateFlow<LocationFix?>(null)
    val lastFix: StateFlow<LocationFix?> = _lastFix.asStateFlow()

    /** Closed sections the router avoids (persisted by the app so manual reports survive restarts). */
    private val _avoidAreas = MutableStateFlow<List<AvoidArea>>(emptyList())
    val avoidAreas: StateFlow<List<AvoidArea>> = _avoidAreas.asStateFlow()

    /** Avoid tolls / highways / ferries – applied to the next routing request. */
    var routeOptions: RouteOptions = RouteOptions()

    private var tracker: RouteProgressTracker? = null
    private var planner: AnnouncementPlanner? = null
    private var routingJob: Job? = null
    private var enrichJob: Job? = null
    private var trafficJob: Job? = null
    private var previewTrafficJob: Job? = null
    private var eta = EtaEstimator()
    /** Router-based (uncorrected) progress of the last fix – baseline for traffic factor. */
    private var lastRawProgress: RouteProgress? = null
    private var announcedDelaySeconds = 0.0
    /** Raw incidents from the last fetch (re-matched when the route changes). */
    private var rawIncidents: List<TrafficIncident> = emptyList()
    private val announcedIncidents = HashSet<String>()
    private var fasterRouteDismissedUntil = 0L
    private var offerCounter = 0
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
                val request = RouteRequest(origin.point, destination.point, origin.movingBearing(), routeOptions)
                var routes = routeAvoiding(request)
                // Uzavírky z živé dopravy: najdeme je na spočítané trase a trasu přepočítáme bez nich
                // (2 kola – objížďka může vést přes další uzavírku).
                repeat(2) {
                    val best = routes.firstOrNull() ?: return@repeat
                    if (!avoidClosuresOn(best, 0.0)) return@repeat
                    routes = routeAvoiding(request)
                }
                if (routes.isEmpty()) {
                    _state.value = NavigationState.Failed(destination, FailureReason.NO_ROUTE, "Trasu se nepodařilo najít.")
                } else if (autoStart) {
                    beginNavigation(destination, routes.first())
                } else {
                    val preview = NavigationState.Preview(destination, routes, avoidedClosures = activeAvoids().size)
                    _state.value = preview
                    fetchPreviewTraffic(preview)
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
        trafficJob?.cancel()
        previewTrafficJob?.cancel()
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

        val raw = tracker.update(fix)
        lastRawProgress = raw.progress
        // ETA: časy routeru × (doprava) × (naučené tempo jízdy).
        val factorRemaining = eta.update(raw.progress.durationRemaining, fix.speedMps, clock())
        val factor = if (raw.progress.durationRemaining > 0) factorRemaining / raw.progress.durationRemaining else 1.0
        val result = raw.copy(
            progress = raw.progress.copy(
                durationRemaining = factorRemaining,
                durationToUpcoming = raw.progress.durationToUpcoming * factor,
            ),
        )
        val limit = result.progress.speedLimitKph
        val speedKph = fix.speedMps?.times(3.6)
        val speeding = limit != null && speedKph != null && speedKph > limit + speedingToleranceKph
        // Nabídka rychlejší trasy platí jen chvíli – pak už může být odbočka za námi.
        val offer = nav.fasterRoute?.takeIf { clock() - it.createdAtMillis < FASTER_ROUTE_OFFER_MS }
        val updated = nav.copy(progress = result.progress, speedMps = fix.speedMps, isSpeeding = speeding, fasterRoute = offer)
        _state.value = updated
        announceIncident(updated, fix.speedMps)
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
        previewTrafficJob?.cancel()
        eta = EtaEstimator()
        announcedDelaySeconds = route.traffic?.delaySeconds ?: 0.0
        announcedIncidents.clear()
        fasterRouteDismissedUntil = 0L
        activateRoute(route)
        _state.value = NavigationState.Navigating(destination, route)
        _events.tryEmit(NavigationEvent.NavigationStarted)
        _lastFix.value?.let(::onLocation)
    }

    /**
     * Driver says "the road ahead is closed" (barrier, local road works TomTom doesn't know).
     * Blocks the next few hundred metres for [MANUAL_AVOID_MS] and finds a detour right away.
     */
    fun reportClosureAhead() {
        val nav = _state.value as? NavigationState.Navigating ?: return
        val progress = nav.progress ?: return
        val g = nav.route.geometry
        // Začátek až kus před autem – zákaz hrany, na které stojíme, by Valhalle znemožnil start.
        val from = progress.distanceAlong + 40
        val nextTurn = nav.route.maneuvers.firstOrNull { it.distanceFromStart > from + 60 }?.distanceFromStart
        val to = minOf(nextTurn ?: Double.MAX_VALUE, from + 400, g.length - 10)
        if (to <= from) return
        val area = AvoidArea(
            id = "manual-${clock()}",
            points = RouteMatching.avoidPoints(g.slice(from, to)),
            expiresAtMillis = clock() + MANUAL_AVOID_MS,
            source = AvoidArea.Source.MANUAL,
            label = nav.upcoming?.primaryName ?: nav.route.maneuvers.getOrNull(progress.upcomingManeuverIndex - 1)?.primaryName,
        )
        addAvoid(area)
        closureReroute("Díky, hledám objížďku.")
    }

    /** Restores persisted avoid areas (expired ones are dropped). */
    fun restoreAvoids(areas: List<AvoidArea>) {
        val now = clock()
        _avoidAreas.value = (areas + _avoidAreas.value).filter { it.expiresAtMillis > now }.distinctBy { it.id }
    }

    fun clearAvoids(source: AvoidArea.Source? = null) {
        _avoidAreas.value = if (source == null) emptyList() else _avoidAreas.value.filter { it.source != source }
    }

    private fun activeAvoids(): List<AvoidArea> {
        val now = clock()
        return _avoidAreas.value.filter { it.expiresAtMillis > now }
    }

    private fun addAvoid(area: AvoidArea): Boolean {
        if (area.points.isEmpty() || _avoidAreas.value.any { it.id == area.id }) return false
        _avoidAreas.value = activeAvoids() + area
        return true
    }

    /** Closures (TomTom ROAD_CLOSED incidents) on [route] ahead of [from] → avoid areas. Returns true if any were new. */
    private suspend fun avoidClosuresOn(route: Route, from: Double): Boolean {
        val provider = traffic() ?: return false
        val incidents = try {
            RouteMatching.boundsAhead(route, from, aheadM = PLANNING_CLOSURE_LOOKAHEAD_M).flatMap { provider.incidents(it) }.distinctBy { it.id }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return false
        }
        rawIncidents = incidents
        return addClosureAvoids(RouteMatching.matchIncidents(route, from, incidents))
    }

    private fun addClosureAvoids(matched: List<RouteIncident>): Boolean =
        matched.filter { it.incident.category == IncidentCategory.ROAD_CLOSED }
            .map { ri ->
                AvoidArea(
                    id = "tt-${ri.incident.id}",
                    points = RouteMatching.avoidPoints(ri.incident.geometry),
                    expiresAtMillis = clock() + TRAFFIC_AVOID_MS,
                    source = AvoidArea.Source.TRAFFIC,
                    label = ri.incident.roadNumbers.firstOrNull(),
                )
            }
            .count(::addAvoid) > 0

    /** Reroute now (no cooldown) because the route goes through a closure. */
    private fun closureReroute(message: String) {
        val nav = _state.value as? NavigationState.Navigating ?: return
        val fix = _lastFix.value ?: return
        speak(message)
        _events.tryEmit(NavigationEvent.ClosureAvoided)
        reroute(nav, fix, force = true)
    }

    /**
     * Routing with the active closures. If the closures make the destination unreachable (the
     * destination itself is behind a barrier), route without them rather than fail.
     */
    private suspend fun routeAvoiding(request: RouteRequest): List<Route> {
        val avoid = activeAvoids().flatMap { it.points }.take(MAX_AVOID_POINTS)
        if (avoid.isEmpty()) return routing.route(request)
        return try {
            routing.route(request.copy(avoid = avoid))
        } catch (e: RoutingException) {
            if (e.kind != RoutingException.Kind.NO_ROUTE) throw e
            routing.route(request)
        }
    }

    /** Driver accepted the faster route (button on phone / alert in the car). */
    fun acceptFasterRoute() {
        val nav = _state.value as? NavigationState.Navigating ?: return
        val offer = nav.fasterRoute ?: return
        activateRoute(offer.route)
        _state.value = nav.copy(
            route = offer.route,
            progress = null,
            fasterRoute = null,
            incidents = RouteMatching.matchIncidents(offer.route, 0.0, rawIncidents),
        )
        speak("Trasa změněna.")
        _events.tryEmit(NavigationEvent.Rerouted)
        _lastFix.value?.let(::onLocation)
    }

    fun dismissFasterRoute() {
        val nav = _state.value as? NavigationState.Navigating ?: return
        if (nav.fasterRoute == null) return
        fasterRouteDismissedUntil = clock() + FASTER_ROUTE_SNOOZE_MS
        _state.value = nav.copy(fasterRoute = null)
    }

    private fun activateRoute(route: Route) {
        routeGeneration++
        tracker = RouteProgressTracker(route, trackerConfig)
        planner = AnnouncementPlanner(route)
        lastRawProgress = null
        eta.onNewRoute()
        enrichSpeedLimits(route, routeGeneration)
        startTrafficUpdates(routeGeneration)
    }

    /** Periodically refreshes traffic for the remaining part of the active route. */
    private fun startTrafficUpdates(generation: Int) {
        trafficJob?.cancel()
        val provider = traffic() ?: return
        trafficJob = scope.launch {
            while (generation == routeGeneration && _state.value is NavigationState.Navigating) {
                val tracker = tracker ?: break
                val raw = lastRawProgress
                val from = raw?.distanceAlong ?: 0.0
                val result = try {
                    provider.traffic(tracker.route, from)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null // doprava je bonus – bez ní jedeme dál podle routeru
                }
                if (result != null && generation == routeGeneration) applyTraffic(result, raw)
                if (generation == routeGeneration) refreshIncidents(provider)
                if (generation == routeGeneration) checkFasterRoute(provider, generation)
                delay(trafficRefreshMs)
            }
        }
    }

    private fun applyTraffic(result: RouteTraffic, raw: RouteProgress?) {
        val tracker = tracker ?: return
        val from = raw?.distanceAlong ?: 0.0
        val closedSpans = result.spans.filter { it.level == TrafficLevel.CLOSED && it.startDistance > from + 50 }
        if (closedSpans.isNotEmpty()) {
            val added = closedSpans.map { span ->
                AvoidArea(
                    id = "span-${tracker.route.id}-${span.startDistance.roundToInt()}",
                    points = RouteMatching.avoidPoints(tracker.route.geometry.slice(span.startDistance, span.endDistance)),
                    expiresAtMillis = clock() + TRAFFIC_AVOID_MS,
                    source = AvoidArea.Source.TRAFFIC,
                )
            }.count(::addAvoid)
            if (added > 0) {
                closureReroute("Na trase je uzavírka, hledám objížďku.")
                return
            }
        }
        val enriched = tracker.route.copy(traffic = result)
        tracker.replaceRouteData(enriched)
        val rawRemaining = raw?.durationRemaining ?: enriched.durationSeconds
        eta.onTraffic(result, rawRemaining)
        val current = _state.value as? NavigationState.Navigating ?: return
        _state.value = current.copy(route = enriched)
        _events.tryEmit(NavigationEvent.TrafficUpdated(result.delaySeconds))
        // Hlásíme jen výrazné zhoršení (kolona před námi), ne každý drobný výkyv.
        val delay = result.delaySeconds
        if (delay >= TRAFFIC_ANNOUNCE_MIN_S && delay - announcedDelaySeconds >= TRAFFIC_ANNOUNCE_STEP_S) {
            val minutes = (delay / 60).roundToInt()
            speak("Na trase je hustý provoz, zdržení asi $minutes ${minutesWord(minutes)}.")
            announcedDelaySeconds = delay
        } else if (delay < announcedDelaySeconds - TRAFFIC_ANNOUNCE_STEP_S) {
            announcedDelaySeconds = delay // kolona se rozpustila → případnou další zase ohlásíme
        }
    }

    private suspend fun refreshIncidents(provider: TrafficProvider) {
        val tracker = tracker ?: return
        val from = lastRawProgress?.distanceAlong ?: 0.0
        val fetched = try {
            RouteMatching.boundsAhead(tracker.route, from).flatMap { provider.incidents(it) }.distinctBy { it.id }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return
        }
        rawIncidents = fetched
        val current = _state.value as? NavigationState.Navigating ?: return
        val matched = RouteMatching.matchIncidents(current.route, from, fetched)
        _state.value = current.copy(incidents = matched)
        // Uzavírka před námi → hned objížďka (nečekáme, až do ní vjedeme).
        if (addClosureAvoids(matched.filter { it.distanceAlong > from + 50 })) closureReroute("Na trase je uzavírka, hledám objížďku.")
    }

    /**
     * Asks the traffic router for the fastest route from here. If it is clearly faster and takes
     * different roads, Valhalla computes it (via pass-through points on TomTom's path) so we keep
     * Czech instructions, and the driver gets an offer.
     */
    private suspend fun checkFasterRoute(provider: TrafficProvider, generation: Int) {
        val nav = _state.value as? NavigationState.Navigating ?: return
        val progress = nav.progress ?: return
        val fix = _lastFix.value ?: return
        if (nav.isRerouting || nav.fasterRoute != null || clock() < fasterRouteDismissedUntil) return
        if (progress.distanceRemaining < FASTER_ROUTE_MIN_REMAINING_M) return
        try {
            val best = provider.fastestRoute(fix.point, fix.movingBearing(), nav.destination.point, routeOptions) ?: return
            val current = progress.durationRemaining
            val saving = current - best.travelTimeSeconds
            if (saving < maxOf(FASTER_ROUTE_MIN_SAVING_S, current * 0.1)) return
            val route = tracker?.route ?: return
            if (RouteMatching.overlap(best.points, route.geometry, progress.distanceAlong) > 0.85) return
            val via = RouteMatching.viaPoints(best.points, route, progress.distanceAlong)
            if (via.isEmpty()) return
            val candidate = routeAvoiding(
                RouteRequest(fix.point, nav.destination.point, fix.movingBearing(), routeOptions.copy(alternatives = 0), via),
            ).firstOrNull() ?: return
            // Valhalla musí opravdu jet tudy, kudy TomTom (jinak by nabídka lhala).
            if (RouteMatching.overlap(candidate.geometry.points, Polyline(best.points), 0.0) < 0.7) return
            if (generation != routeGeneration) return
            val latest = _state.value as? NavigationState.Navigating ?: return
            val offer = FasterRouteOffer(++offerCounter, candidate, saving, clock())
            _state.value = latest.copy(fasterRoute = offer)
            _events.tryEmit(NavigationEvent.FasterRouteFound(offer))
            val minutes = (saving / 60).roundToInt()
            speak("Je k dispozici rychlejší trasa, ušetříte asi $minutes ${minutesWord(minutes)}.")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Bez nabídky – současná trasa pořád platí.
        }
    }

    /** Voice warning for the nearest serious incident, once per incident, ~1.5–4 km ahead. */
    private fun announceIncident(nav: NavigationState.Navigating, speedMps: Double?) {
        val progress = nav.progress ?: return
        val next = nav.nextIncident ?: return
        val incident = next.incident
        if (incident.id in announcedIncidents || incident.category !in ANNOUNCED_CATEGORIES) return
        val distance = next.distanceAlong - progress.distanceAlong
        val window = ((speedMps ?: 15.0) * 90).coerceIn(1_500.0, 4_000.0)
        if (distance > window || distance < 0) return
        announcedIncidents += incident.id
        val what = IncidentTexts.spoken(incident.category)
        val delay = incident.delaySeconds?.let { (it / 60).roundToInt() }?.takeIf { it >= 2 }
        val tail = delay?.let { " Zdržení asi $it ${minutesWord(it)}." }.orEmpty()
        speak("Za ${CzechFormat.spokenDistance(distance)} $what.$tail")
    }

    /** Preview: traffic-aware times for every alternative (in parallel). */
    private fun fetchPreviewTraffic(preview: NavigationState.Preview) {
        previewTrafficJob?.cancel()
        val provider = traffic() ?: return
        previewTrafficJob = scope.launch {
            val results = preview.routes.map { route ->
                async {
                    try {
                        provider.traffic(route, 0.0)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        null
                    }
                }
            }.awaitAll()
            val current = _state.value as? NavigationState.Preview ?: return@launch
            if (current.routes.map { it.id } != preview.routes.map { it.id } || current.destination != preview.destination) return@launch
            _state.value = current.copy(routes = current.routes.mapIndexed { i, r -> results[i]?.let { r.copy(traffic = it) } ?: r })
        }
    }

    private fun reroute(nav: NavigationState.Navigating, fix: LocationFix, force: Boolean = false) {
        if (nav.isRerouting) return
        val now = clock()
        if (!force && now - lastRerouteAt < rerouteCooldownMs) return
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
                val newRoute = routeAvoiding(request).firstOrNull()
                    ?: throw RoutingException(RoutingException.Kind.NO_ROUTE, "No route")
                val current = _state.value as? NavigationState.Navigating ?: return@launch
                activateRoute(newRoute)
                _state.value = current.copy(
                    route = newRoute,
                    progress = null,
                    isRerouting = false,
                    rerouteFailed = false,
                    fasterRoute = null,
                    incidents = RouteMatching.matchIncidents(newRoute, 0.0, rawIncidents),
                )
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
            // Z trackeru – aby se nepřepsala mezitím stažená doprava.
            val enriched = (tracker?.route ?: route).copy(speedLimits = spans)
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
        trafficJob?.cancel()
        tracker = null
        planner = null
        _state.value = NavigationState.Arrived(destination)
        _events.tryEmit(NavigationEvent.Arrived)
    }

    private fun minutesWord(n: Int) = when (n) {
        1 -> "minuta"
        in 2..4 -> "minuty"
        else -> "minut"
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

    private companion object {
        const val TRAFFIC_ANNOUNCE_MIN_S = 300.0
        const val TRAFFIC_ANNOUNCE_STEP_S = 180.0
        const val FASTER_ROUTE_MIN_SAVING_S = 300.0
        const val FASTER_ROUTE_MIN_REMAINING_M = 5_000.0
        const val FASTER_ROUTE_OFFER_MS = 60_000L
        const val FASTER_ROUTE_SNOOZE_MS = 10 * 60_000L
        const val TRAFFIC_AVOID_MS = 3 * 60 * 60_000L
        const val MANUAL_AVOID_MS = 14 * 24 * 60 * 60_000L
        const val MAX_AVOID_POINTS = 45
        const val PLANNING_CLOSURE_LOOKAHEAD_M = 200_000.0
        val ANNOUNCED_CATEGORIES = setOf(
            IncidentCategory.ACCIDENT, IncidentCategory.JAM, IncidentCategory.ROAD_CLOSED, IncidentCategory.LANE_CLOSED,
            IncidentCategory.ROAD_WORKS, IncidentCategory.BROKEN_DOWN_VEHICLE, IncidentCategory.DANGER,
        )
    }

    private fun RoutingException.Kind.toMessage() = when (this) {
        RoutingException.Kind.NETWORK -> "Nelze se spojit se serverem tras. Zkontroluj připojení."
        RoutingException.Kind.NO_ROUTE -> "Do tohoto cíle nevede žádná trasa."
        RoutingException.Kind.SERVER -> "Server tras vrátil chybu."
    }
}
