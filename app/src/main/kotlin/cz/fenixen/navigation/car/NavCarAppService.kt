package cz.fenixen.navigation.car

import android.content.Intent
import android.content.pm.ApplicationInfo
import androidx.car.app.CarAppService
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.navigation.NavigationManager
import androidx.car.app.navigation.NavigationManagerCallback
import androidx.car.app.validation.HostValidator
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import cz.fenixen.navigation.R
import cz.fenixen.navigation.appGraph
import cz.fenixen.navigation.core.model.Place
import cz.fenixen.navigation.core.navigation.NavigationState
import cz.fenixen.navigation.core.search.GeoUri
import kotlinx.coroutines.launch

/** Entry point for Android Auto (and Android Automotive OS hosts). */
class NavCarAppService : CarAppService() {
    override fun createHostValidator(): HostValidator {
        val debuggable = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        // V debug buildu povolíme i Desktop Head Unit; release jen ověřené hosty od Googlu.
        return if (debuggable) {
            HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
        } else {
            HostValidator.Builder(applicationContext)
                .addAllowedHosts(R.array.car_hosts_allowlist)
                .build()
        }
    }

    override fun onCreateSession(): Session = NavCarSession()
}

class NavCarSession : Session() {
    private lateinit var renderer: CarMapRenderer
    private var releaseLocation: (() -> Unit)? = null
    private var tripActive = false

    override fun onCreateScreen(intent: Intent): Screen {
        val graph = carContext.appGraph
        renderer = CarMapRenderer(carContext, lifecycle, graph)

        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                releaseLocation = graph.locationCoordinator.acquire()
            }

            override fun onStop(owner: LifecycleOwner) {
                releaseLocation?.invoke()
                releaseLocation = null
            }
        })

        // Přístrojový štít / HUD: NavigationManager dostává Trip s aktuálním krokem a ETA.
        val navigationManager = carContext.getCarService(NavigationManager::class.java)
        navigationManager.setNavigationManagerCallback(object : NavigationManagerCallback {
            override fun onStopNavigation() = graph.session.stop()

            // DHU „autodrive“ → zapneme simulaci jízdy po trase.
            override fun onAutoDriveEnabled() = graph.settings.setSimulateDriving(true)
        })
        lifecycleScope.launch {
            graph.session.state.collect { state ->
                if (state is NavigationState.Navigating) {
                    if (!tripActive) {
                        navigationManager.navigationStarted()
                        tripActive = true
                    }
                    navigationManager.updateTrip(CarModels.trip(state))
                } else if (tripActive) {
                    navigationManager.navigationEnded()
                    tripActive = false
                }
            }
        }

        handleNavigateIntent(intent)
        return MainCarScreen(carContext, graph, renderer)
    }

    override fun onNewIntent(intent: Intent) {
        handleNavigateIntent(intent)
    }

    override fun onCarConfigurationChanged(newConfiguration: android.content.res.Configuration) {
        renderer.onCarConfigurationChanged()
    }

    /** "Ok Google, naviguj do Brna" → host sends ACTION_NAVIGATE with a geo: URI. */
    private fun handleNavigateIntent(intent: Intent) {
        if (intent.action != CarContext.ACTION_NAVIGATE) return
        val geo = intent.data?.toString()?.let(GeoUri::parse) ?: return
        val graph = carContext.appGraph
        val point = geo.point
        if (point != null) {
            graph.session.planRoute(Place(geo.label ?: geo.query ?: "Cíl", point), autoStart = true)
            return
        }
        val query = geo.query ?: return
        lifecycleScope.launch {
            val place = runCatching { graph.geocoder.search(query, graph.session.lastFix.value?.point, limit = 1).firstOrNull() }.getOrNull()
            place?.let { graph.session.planRoute(it, autoStart = true) }
        }
    }
}
