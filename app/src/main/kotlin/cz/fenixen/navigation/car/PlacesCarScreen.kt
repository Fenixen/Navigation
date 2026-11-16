// setTitle/setHeaderAction jsou deprecated ve prospěch Header (host API 7+); kvůli starším
// jednotkám v autech používáme původní API.
@file:Suppress("DEPRECATION")

package cz.fenixen.navigation.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.lifecycle.lifecycleScope
import cz.fenixen.navigation.AppGraph
import cz.fenixen.navigation.R
import cz.fenixen.navigation.core.model.Place
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/** Home, Work and recent destinations – the most used screen while driving (no typing needed). */
class PlacesCarScreen(
    carContext: CarContext,
    private val graph: AppGraph,
    private val renderer: CarMapRenderer,
) : Screen(carContext) {

    init {
        lifecycleScope.launch { graph.places.places.drop(1).collect { invalidate() } }
    }

    override fun onGetTemplate(): Template {
        val saved = graph.places.places.value
        val list = ItemList.Builder().setNoItemsMessage("Zatím žádná oblíbená ani nedávná místa")
        saved.home?.let { list.addItem(row(it, carContext.getString(R.string.home), R.drawable.ic_home)) }
        saved.work?.let { list.addItem(row(it, carContext.getString(R.string.work), R.drawable.ic_work)) }
        saved.recents.take(6).forEach { list.addItem(row(it, null, R.drawable.ic_history)) }
        return ListTemplate.Builder()
            .setTitle(carContext.getString(R.string.favorites))
            .setHeaderAction(Action.BACK)
            .setSingleList(list.build())
            .build()
    }

    private fun row(place: Place, label: String?, icon: Int): Row =
        Row.Builder()
            .setTitle(label ?: place.name)
            .addText(if (label != null) place.name else place.address ?: "")
            .setImage(CarModels.icon(carContext, icon))
            .setOnClickListener {
                graph.session.planRoute(place)
                screenManager.push(RoutePreviewCarScreen(carContext, graph, renderer))
            }
            .build()
}
