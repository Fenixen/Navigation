package cz.fenixen.navigation.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import cz.fenixen.navigation.appGraph
import cz.fenixen.navigation.core.model.Place
import cz.fenixen.navigation.core.navigation.NavigationState
import cz.fenixen.navigation.core.search.GeoUri
import cz.fenixen.navigation.core.search.GeocodingException
import cz.fenixen.navigation.data.DisplayKind
import cz.fenixen.navigation.data.MapTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

sealed interface SearchUiState {
    data object Idle : SearchUiState
    data object Loading : SearchUiState
    data class Results(val places: List<Place>) : SearchUiState
    data class Error(val message: String) : SearchUiState
}

enum class Screen { MAP, SEARCH, SETTINGS }

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val graph = app.appGraph
    private val session = graph.session

    val navState = session.state
    val lastFix = session.lastFix
    val settings = graph.settings.current
    val places = graph.places.places
    val cameraState = graph.phoneCamera.state
    val camera = graph.phoneCamera

    private val _screen = MutableStateFlow(Screen.MAP)
    val screen: StateFlow<Screen> = _screen.asStateFlow()

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _search = MutableStateFlow<SearchUiState>(SearchUiState.Idle)
    val search: StateFlow<SearchUiState> = _search.asStateFlow()

    init {
        observeSearch()
    }

    @OptIn(FlowPreview::class)
    private fun observeSearch() {
        viewModelScope.launch {
            // Debounce: nehledáme při každém písmenu, ale 250 ms po posledním. collectLatest
            // zruší rozběhnutý dotaz (OkHttp call se opravdu přeruší), když uživatel píše dál.
            _query.debounce(250).distinctUntilChanged().collectLatest { q ->
                if (q.trim().length < 2) {
                    _search.value = SearchUiState.Idle
                    return@collectLatest
                }
                _search.value = SearchUiState.Loading
                _search.value = try {
                    SearchUiState.Results(graph.geocoder.search(q, near = lastFix.value?.point))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: GeocodingException) {
                    SearchUiState.Error("Vyhledávání není dostupné. Zkontroluj připojení.")
                } catch (e: Exception) {
                    SearchUiState.Error("Vyhledávání selhalo.")
                }
            }
        }
    }

    fun openSearch() {
        _screen.value = Screen.SEARCH
    }

    fun openSettings() {
        _screen.value = Screen.SETTINGS
    }

    fun back(): Boolean {
        if (_screen.value != Screen.MAP) {
            _screen.value = Screen.MAP
            return true
        }
        return when (navState.value) {
            is NavigationState.Preview, is NavigationState.Planning, is NavigationState.Failed,
            is NavigationState.Arrived -> {
                session.stop()
                true
            }
            else -> false
        }
    }

    fun onQueryChange(q: String) {
        _query.value = q
    }

    fun navigateTo(place: Place) {
        _screen.value = Screen.MAP
        _query.value = ""
        session.planRoute(place)
    }

    fun selectRoute(index: Int) = session.selectRoute(index)
    fun startNavigation() = session.startNavigation()
    fun stopNavigation() = session.stop()
    fun retry() = session.retry()

    fun setHome(place: Place) = graph.places.setHome(place)
    fun setWork(place: Place) = graph.places.setWork(place)

    fun toggleVoice() {
        val enabled = !settings.value.voiceEnabled
        graph.settings.setVoiceEnabled(enabled)
        if (!enabled) graph.voice.stop()
    }

    /** Changing avoidances in preview re-plans immediately with the new options. */
    fun setAvoidance(tolls: Boolean? = null, highways: Boolean? = null, ferries: Boolean? = null) {
        tolls?.let(graph.settings::setAvoidTolls)
        highways?.let(graph.settings::setAvoidHighways)
        ferries?.let(graph.settings::setAvoidFerries)
        val current = session.routeOptions
        session.routeOptions = current.copy(
            avoidTolls = tolls ?: current.avoidTolls,
            avoidHighways = highways ?: current.avoidHighways,
            avoidFerries = ferries ?: current.avoidFerries,
        )
        (navState.value as? NavigationState.Preview)?.let { session.planRoute(it.destination) }
    }

    fun setOrientationFromCamera() {
        graph.settings.setOrientation(camera.state.value.orientation)
    }

    fun clearZoomLock(display: DisplayKind) {
        when (display) {
            DisplayKind.PHONE -> graph.phoneCamera.enableAutoZoom()
            DisplayKind.CAR -> graph.carCamera.enableAutoZoom()
        }
    }

    fun setMapTheme(theme: MapTheme) = graph.settings.setMapTheme(theme)
    fun setVoiceEnabled(v: Boolean) = graph.settings.setVoiceEnabled(v)
    fun setRoutingUrl(url: String) = graph.settings.setRoutingUrl(url)
    fun setGeocoderUrl(url: String) = graph.settings.setGeocoderUrl(url)
    fun setSimulateDriving(v: Boolean) = graph.settings.setSimulateDriving(v)

    /** "Navigate here" from another app (geo: URI). */
    fun handleGeoUri(uri: String) {
        val intent = GeoUri.parse(uri) ?: return
        val point = intent.point
        if (point != null) {
            navigateTo(Place(intent.label ?: intent.query ?: "Vybrané místo", point))
            return
        }
        val query = intent.query ?: return
        viewModelScope.launch {
            val place = runCatching { graph.geocoder.search(query, lastFix.value?.point, limit = 1).firstOrNull() }
                .getOrNull()
            if (place != null) navigateTo(place) else {
                _query.value = query
                _screen.value = Screen.SEARCH
            }
        }
    }

    fun onLocationPermissionGranted() = graph.locationCoordinator.onPermissionChanged()

    fun acquireLocation(): () -> Unit = graph.locationCoordinator.acquire()
}
