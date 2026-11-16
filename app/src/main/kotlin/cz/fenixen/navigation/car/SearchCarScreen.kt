package cz.fenixen.navigation.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ItemList
import androidx.car.app.model.Row
import androidx.car.app.model.SearchTemplate
import androidx.car.app.model.Template
import androidx.lifecycle.lifecycleScope
import cz.fenixen.navigation.AppGraph
import cz.fenixen.navigation.R
import cz.fenixen.navigation.core.format.CzechFormat
import cz.fenixen.navigation.core.geo.Geo
import cz.fenixen.navigation.core.model.Place
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Destination search (keyboard is available only while parked – the host enforces that). */
class SearchCarScreen(
    carContext: CarContext,
    private val graph: AppGraph,
    private val renderer: CarMapRenderer,
) : Screen(carContext) {
    private var results: List<Place>? = null
    private var loading = false
    private var error: String? = null
    private var job: Job? = null

    override fun onGetTemplate(): Template {
        val builder = SearchTemplate.Builder(object : SearchTemplate.SearchCallback {
            override fun onSearchTextChanged(searchText: String) = search(searchText, debounceMs = 350)
            override fun onSearchSubmitted(searchText: String) = search(searchText, debounceMs = 0)
        })
            .setHeaderAction(Action.BACK)
            .setSearchHint(carContext.getString(R.string.search_hint))
            .setShowKeyboardByDefault(true)

        if (loading && results == null) {
            builder.setLoading(true)
        } else {
            val places = results ?: recentPlaces()
            builder.setItemList(placeList(places, error ?: carContext.getString(R.string.no_results)))
        }
        return builder.build()
    }

    private fun recentPlaces(): List<Place> {
        val saved = graph.places.places.value
        return listOfNotNull(saved.home, saved.work) + saved.recents
    }

    private fun search(text: String, debounceMs: Long) {
        job?.cancel()
        if (text.trim().length < 2) {
            results = null
            loading = false
            error = null
            invalidate()
            return
        }
        job = lifecycleScope.launch {
            delay(debounceMs)
            loading = true
            invalidate()
            try {
                results = graph.geocoder.search(text, graph.session.lastFix.value?.point, limit = 6)
                error = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                results = emptyList()
                error = "Vyhledávání není dostupné"
            }
            loading = false
            invalidate()
        }
    }

    private fun placeList(places: List<Place>, emptyMessage: String): ItemList {
        val me = graph.session.lastFix.value?.point
        val list = ItemList.Builder().setNoItemsMessage(emptyMessage)
        places.take(6).forEach { place ->
            val distance = me?.let { CzechFormat.distance(Geo.distance(it, place.point)) }
            list.addItem(
                Row.Builder()
                    .setTitle(place.name)
                    .apply {
                        val text = listOfNotNull(distance, place.address).joinToString(" · ")
                        if (text.isNotEmpty()) addText(text)
                    }
                    .setOnClickListener {
                        graph.session.planRoute(place)
                        screenManager.push(RoutePreviewCarScreen(carContext, graph, renderer))
                    }
                    .build(),
            )
        }
        return list.build()
    }
}
