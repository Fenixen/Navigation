// RoutePreviewNavigationTemplate je od Car App 1.7 deprecated (nástupce MapWithContentTemplate
// vyžaduje host API 7). Starší jednotky v autech ho ale stále potřebují → vědomě ponecháno.
@file:Suppress("DEPRECATION")

package cz.fenixen.navigation.car

import androidx.activity.OnBackPressedCallback
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.ItemList
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.RoutePreviewNavigationTemplate
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import cz.fenixen.navigation.AppGraph
import cz.fenixen.navigation.R
import cz.fenixen.navigation.core.format.CzechFormat
import cz.fenixen.navigation.core.navigation.NavigationState
import kotlinx.coroutines.launch

/** Route alternatives in the car. Selecting a row highlights the route on the map. */
class RoutePreviewCarScreen(
    carContext: CarContext,
    private val graph: AppGraph,
    private val renderer: CarMapRenderer,
) : Screen(carContext) {
    private val session = graph.session

    init {
        carContext.onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // Zrušení náhledu → stav Idle → obrazovku zavře collector níže (jen jednou).
                if (session.state.value is NavigationState.Idle) screenManager.pop() else session.stop()
            }
        })
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                session.state.collect { state ->
                    when (state) {
                        is NavigationState.Navigating, is NavigationState.Arrived -> screenManager.popToRoot()
                        NavigationState.Idle -> screenManager.pop()
                        else -> invalidate()
                    }
                }
            }
        }
    }

    override fun onGetTemplate(): Template {
        return when (val state = session.state.value) {
            is NavigationState.Preview -> previewTemplate(state)
            is NavigationState.Failed -> MessageTemplate.Builder(state.message)
                .setTitle(state.destination?.name ?: carContext.getString(R.string.app_name))
                .setHeaderAction(Action.BACK)
                .addAction(Action.Builder().setTitle(carContext.getString(R.string.retry)).setOnClickListener { session.retry() }.build())
                .build()
            else -> RoutePreviewNavigationTemplate.Builder()
                .setTitle((state as? NavigationState.Planning)?.destination?.name ?: carContext.getString(R.string.planning))
                .setHeaderAction(Action.BACK)
                .setLoading(true)
                .build()
        }
    }

    private fun previewTemplate(state: NavigationState.Preview): Template {
        val fastest = state.routes.minOf { it.durationSeconds }
        val list = ItemList.Builder()
        state.routes.forEach { route ->
            val via = route.summary.takeIf { it.isNotBlank() }?.let { " · přes $it" }.orEmpty()
            val badges = listOfNotNull(
                carContext.getString(R.string.toll).takeIf { route.hasToll },
                carContext.getString(R.string.ferry).takeIf { route.hasFerry },
            ).joinToString(" · ")
            list.addItem(
                Row.Builder()
                    .setTitle(CzechFormat.duration(route.durationSeconds) + if (route.durationSeconds == fastest) " · ${carContext.getString(R.string.fastest)}" else "")
                    .addText(CzechFormat.distance(route.distanceMeters) + via)
                    .apply { if (badges.isNotEmpty()) addText(badges) }
                    .build(),
            )
        }
        list.setSelectedIndex(state.selectedIndex)
        list.setOnSelectedListener { index -> session.selectRoute(index) }

        return RoutePreviewNavigationTemplate.Builder()
            .setTitle(state.destination.name)
            .setHeaderAction(Action.BACK)
            .setItemList(list.build())
            .setNavigateAction(
                Action.Builder()
                    .setTitle(carContext.getString(R.string.start))
                    .setIcon(CarModels.icon(carContext, R.drawable.ic_navigation))
                    .setOnClickListener { session.startNavigation() }
                    .build(),
            )
            .setMapActionStrip(
                ActionStrip.Builder()
                    .addAction(Action.PAN)
                    .addAction(Action.Builder().setIcon(CarModels.icon(carContext, R.drawable.ic_add)).setOnClickListener { renderer.controller?.zoomIn() }.build())
                    .addAction(Action.Builder().setIcon(CarModels.icon(carContext, R.drawable.ic_remove)).setOnClickListener { renderer.controller?.zoomOut() }.build())
                    .build(),
            )
            .setPanModeListener { }
            .build()
    }
}
