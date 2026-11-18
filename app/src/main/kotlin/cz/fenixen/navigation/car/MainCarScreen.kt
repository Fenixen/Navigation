// setTitle/setHeaderAction jsou deprecated ve prospěch Header (host API 7+); kvůli starším
// jednotkám v autech používáme původní API.
@file:Suppress("DEPRECATION")

package cz.fenixen.navigation.car

import android.Manifest
import android.content.pm.PackageManager
import androidx.car.app.AppManager
import androidx.car.app.CarContext
import androidx.car.app.versioning.CarAppApiLevels
import androidx.car.app.model.Alert
import androidx.car.app.model.AlertCallback
import androidx.car.app.model.CarText
import cz.fenixen.navigation.core.navigation.FasterRouteOffer
import kotlin.math.roundToInt
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.MessageInfo
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import cz.fenixen.navigation.AppGraph
import cz.fenixen.navigation.R
import cz.fenixen.navigation.core.navigation.CameraState
import cz.fenixen.navigation.core.navigation.FollowMode
import cz.fenixen.navigation.core.navigation.NavigationState
import cz.fenixen.navigation.core.navigation.ZoomPolicy
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Root car screen. It is *state driven*: whatever the shared [NavigationSession] is doing
 * (idle, navigating, arrived …) is rendered here, no matter if it was started on the phone or in the car.
 */
class MainCarScreen(
    carContext: CarContext,
    private val graph: AppGraph,
    private val renderer: CarMapRenderer,
) : Screen(carContext) {

    private val session = graph.session

    init {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                val cameraUi = graph.carCamera.state.map { it.followMode to (it.zoomPolicy is ZoomPolicy.Locked) }.distinctUntilChanged()
                combine(session.state, cameraUi, graph.settings.current.map { it.voiceEnabled }) { state, _, _ -> state }
                    .collect { state ->
                        // Náhled trasy spuštěný z telefonu ukážeme i v autě.
                        val onTop = screenManager.top === this@MainCarScreen
                        if (onTop && (state is NavigationState.Planning || state is NavigationState.Preview)) {
                            screenManager.push(RoutePreviewCarScreen(carContext, graph, renderer))
                        }
                        (state as? NavigationState.Navigating)?.fasterRoute?.let(::offerFasterRoute)
                        invalidate()
                    }
            }
        }
    }

    override fun onGetTemplate(): Template {
        if (!hasLocationPermission()) return permissionTemplate()
        val state = session.state.value
        val builder = NavigationTemplate.Builder()
            .setMapActionStrip(mapActions(graph.carCamera.state.value))
            .setPanModeListener { }
        when (state) {
            is NavigationState.Navigating -> {
                builder.setNavigationInfo(CarModels.routingInfo(state))
                CarModels.destinationEstimate(state)?.let(builder::setDestinationTravelEstimate)
                builder.setActionStrip(navigatingActions())
            }
            is NavigationState.Arrived -> {
                builder.setNavigationInfo(
                    MessageInfo.Builder(carContext.getString(R.string.arrived))
                        .setText(state.destination.name)
                        .setImage(CarModels.icon(carContext, R.drawable.ic_place))
                        .build(),
                )
                builder.setActionStrip(
                    ActionStrip.Builder().addAction(
                        Action.Builder().setTitle(carContext.getString(R.string.done)).setOnClickListener { session.stop() }.build(),
                    ).build(),
                )
            }
            is NavigationState.Failed -> {
                builder.setNavigationInfo(MessageInfo.Builder(state.message).build())
                builder.setActionStrip(idleActions())
            }
            else -> builder.setActionStrip(idleActions())
        }
        return builder.build()
    }

    private fun idleActions(): ActionStrip = ActionStrip.Builder()
        .addAction(
            Action.Builder()
                .setTitle(carContext.getString(R.string.search))
                .setIcon(CarModels.icon(carContext, R.drawable.ic_search))
                .setOnClickListener { screenManager.push(SearchCarScreen(carContext, graph, renderer)) }
                .build(),
        )
        .addAction(
            Action.Builder()
                .setIcon(CarModels.icon(carContext, R.drawable.ic_star))
                .setOnClickListener { screenManager.push(PlacesCarScreen(carContext, graph, renderer)) }
                .build(),
        )
        .build()

    private fun navigatingActions(): ActionStrip {
        val voice = graph.settings.current.value.voiceEnabled
        return ActionStrip.Builder()
            .addAction(
                Action.Builder()
                    .setTitle(carContext.getString(R.string.end_navigation))
                    .setOnClickListener { session.stop() }
                    .build(),
            )
            .addAction(
                Action.Builder()
                    .setIcon(CarModels.icon(carContext, if (voice) R.drawable.ic_volume_up else R.drawable.ic_volume_off))
                    .setOnClickListener {
                        graph.settings.setVoiceEnabled(!voice)
                        if (voice) graph.voice.stop()
                    }
                    .build(),
            )
            .addAction(
                Action.Builder()
                    .setIcon(CarModels.icon(carContext, R.drawable.ic_overview))
                    .setOnClickListener { renderer.controller?.showOverview() }
                    .build(),
            )
            .build()
    }

    /**
     * Map buttons: pan, +, −, and the zoom-lock toggle. Every +/− while following *locks* that
     * zoom (max zoom = press + until it stops – and it stays there). When the map was panned,
     * the 4th slot becomes "recenter".
     */
    private fun mapActions(camera: CameraState): ActionStrip {
        val fourth = if (camera.followMode != FollowMode.FOLLOW) {
            Action.Builder()
                .setIcon(CarModels.icon(carContext, R.drawable.ic_my_location))
                .setOnClickListener { renderer.controller?.recenter() }
                .build()
        } else {
            val locked = camera.zoomPolicy is ZoomPolicy.Locked
            Action.Builder()
                .setIcon(CarModels.icon(carContext, if (locked) R.drawable.ic_lock else R.drawable.ic_auto_zoom))
                .setOnClickListener {
                    val c = renderer.controller ?: return@setOnClickListener
                    if (locked) {
                        c.enableAutoZoom()
                        toast("Automatický zoom")
                    } else {
                        c.lockCurrentZoom()
                        toast("Zoom zamčen")
                    }
                }
                .build()
        }
        return ActionStrip.Builder()
            .addAction(Action.PAN)
            .addAction(
                Action.Builder()
                    .setIcon(CarModels.icon(carContext, R.drawable.ic_add))
                    .setOnClickListener { renderer.controller?.zoomIn() }
                    .build(),
            )
            .addAction(
                Action.Builder()
                    .setIcon(CarModels.icon(carContext, R.drawable.ic_remove))
                    .setOnClickListener { renderer.controller?.zoomOut() }
                    .build(),
            )
            .addAction(fourth)
            .build()
    }

    private var shownOfferId = -1

    /** Faster route in the car: host alert with Use / No (API 5+), otherwise a toast + voice. */
    private fun offerFasterRoute(offer: FasterRouteOffer) {
        if (offer.id == shownOfferId) return
        shownOfferId = offer.id
        val minutes = (offer.savingSeconds / 60).roundToInt()
        val title = "Rychlejší trasa: −$minutes min"
        if (carContext.carAppApiLevel < CarAppApiLevels.LEVEL_5) {
            toast(title)
            return
        }
        val alert = Alert.Builder(offer.id, CarText.create(title), 30_000)
            .apply { offer.route.summary.takeIf { it.isNotBlank() }?.let { setSubtitle(CarText.create("přes $it")) } }
            .addAction(Action.Builder().setTitle("Použít").setOnClickListener { session.acceptFasterRoute() }.build())
            .addAction(Action.Builder().setTitle("Ne").setOnClickListener { session.dismissFasterRoute() }.build())
            .setCallback(object : AlertCallback {
                override fun onCancel(reason: Int) {
                    if (reason == AlertCallback.REASON_TIMEOUT) session.dismissFasterRoute()
                }
                override fun onDismiss() = Unit
            })
            .build()
        carContext.getCarService(AppManager::class.java).showAlert(alert)
    }

    private fun toast(text: String) = CarToast.makeText(carContext, text, CarToast.LENGTH_SHORT).show()

    private fun hasLocationPermission() =
        ContextCompat.checkSelfPermission(carContext, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            graph.settings.current.value.simulateDriving

    private fun permissionTemplate(): Template =
        MessageTemplate.Builder(carContext.getString(R.string.location_needed))
            .setTitle(carContext.getString(R.string.app_name))
            .addAction(
                Action.Builder()
                    .setTitle(carContext.getString(R.string.grant))
                    .setOnClickListener {
                        // Systémový dialog se zobrazí na telefonu (v autě se oprávnění udělit nedá).
                        carContext.requestPermissions(
                            listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                        ) { granted, _ ->
                            if (granted.isNotEmpty()) graph.locationCoordinator.onPermissionChanged()
                            invalidate()
                        }
                    }
                    .build(),
            )
            .build()
}
