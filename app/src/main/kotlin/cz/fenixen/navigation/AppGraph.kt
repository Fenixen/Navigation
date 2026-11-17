package cz.fenixen.navigation

import android.app.Application
import cz.fenixen.navigation.core.model.RouteOptions
import cz.fenixen.navigation.core.navigation.CameraController
import cz.fenixen.navigation.core.navigation.NavigationEvent
import cz.fenixen.navigation.core.navigation.NavigationSession
import cz.fenixen.navigation.core.navigation.NavigationState
import cz.fenixen.navigation.core.routing.ValhallaRoutingProvider
import cz.fenixen.navigation.core.search.PhotonGeocoder
import cz.fenixen.navigation.core.traffic.TomTomTrafficProvider
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import cz.fenixen.navigation.data.DisplayKind
import cz.fenixen.navigation.data.PlacesRepository
import cz.fenixen.navigation.data.SettingsRepository
import cz.fenixen.navigation.location.LocationCoordinator
import cz.fenixen.navigation.location.LocationRepository
import cz.fenixen.navigation.service.NavigationService
import cz.fenixen.navigation.voice.VoiceGuide
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import okhttp3.Cache
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Manual dependency injection: one object graph per process.
 *
 * Phone UI and Android Auto run in the *same process*, so they share one [NavigationSession]
 * – start a trip on the phone and the car shows it immediately (and vice versa).
 * For an app this size manual DI is clearer than Hilt; the seams (interfaces in :core)
 * are what matters for testability.
 */
class AppGraph(app: Application) {
    /** Process-wide scope on the main thread – the session is main-thread confined. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val settings = SettingsRepository(app, appScope)
    val places = PlacesRepository(app, appScope)

    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS)
        .cache(Cache(File(app.cacheDir, "http"), 10L * 1024 * 1024))
        .addInterceptor { chain ->
            // Veřejné OSM služby vyžadují identifikovatelný User-Agent.
            chain.proceed(chain.request().newBuilder().header("User-Agent", USER_AGENT).build())
        }
        .build()

    val routing = ValhallaRoutingProvider(http, baseUrl = { settings.current.value.routingUrl })
    val geocoder = PhotonGeocoder(http, baseUrl = { settings.current.value.geocoderUrl })
    val traffic = TomTomTrafficProvider(http, apiKey = { settings.current.value.tomtomKey })
    val session = NavigationSession(
        routing = routing,
        scope = appScope,
        traffic = { traffic.takeIf { settings.current.value.hasTraffic } },
    )

    /** TomTom key for the traffic tiles on the map (null = overlay off). */
    val trafficTilesKey: StateFlow<String?> = settings.current
        .map { s -> s.tomtomKey.takeIf { s.hasTraffic && s.trafficOnMap } }
        .distinctUntilChanged()
        .stateIn(appScope, SharingStarted.Eagerly, null)

    val location = LocationRepository(app)
    val locationCoordinator = LocationCoordinator(appScope, location, session, settings)
    val voice = VoiceGuide(app, isEnabled = { settings.current.value.voiceEnabled })

    /** Separate cameras: the car screen has a different size/DPI → its own zoom lock. */
    val phoneCamera = CameraController(onZoomLockChanged = { settings.setZoomLock(DisplayKind.PHONE, it) })
    val carCamera = CameraController(onZoomLockChanged = { settings.setZoomLock(DisplayKind.CAR, it) })

    init {
        appScope.launch {
            val s = settings.load()
            phoneCamera.restore(s.phoneZoomLock, s.orientation)
            carCamera.restore(s.carZoomLock, s.orientation)
        }
        appScope.launch {
            settings.current.collect {
                session.routeOptions = RouteOptions(
                    avoidTolls = it.avoidTolls,
                    avoidHighways = it.avoidHighways,
                    avoidFerries = it.avoidFerries,
                )
            }
        }
        appScope.launch {
            session.events.collect { event ->
                when (event) {
                    is NavigationEvent.Speak -> voice.speak(event.text)
                    NavigationEvent.NavigationEnded -> voice.stop()
                    else -> Unit
                }
            }
        }
        appScope.launch {
            session.state
                .map { (it as? NavigationState.Navigating)?.destination }
                .distinctUntilChanged()
                .collect { destination ->
                    if (destination != null) {
                        places.addRecent(destination)
                        NavigationService.start(app)
                    }
                }
        }
    }

    private companion object {
        const val USER_AGENT = "Navigace-Android/${BuildConfig.VERSION_NAME}"
    }
}
