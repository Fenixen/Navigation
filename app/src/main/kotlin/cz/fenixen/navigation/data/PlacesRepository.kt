package cz.fenixen.navigation.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import cz.fenixen.navigation.core.geo.Geo
import cz.fenixen.navigation.core.geo.GeoPoint
import cz.fenixen.navigation.core.model.Place
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

data class SavedPlaces(
    val home: Place? = null,
    val work: Place? = null,
    val recents: List<Place> = emptyList(),
)

private val Context.placesStore: DataStore<Preferences> by preferencesDataStore(name = "places")

/** Home / work / recent destinations. Stored as small JSON blobs (android's built-in org.json). */
class PlacesRepository(context: Context, private val scope: CoroutineScope) {
    private val store = context.applicationContext.placesStore
    private val homeKey = stringPreferencesKey("home")
    private val workKey = stringPreferencesKey("work")
    private val recentsKey = stringPreferencesKey("recents")

    val places: StateFlow<SavedPlaces> = store.data.map { p ->
        SavedPlaces(
            home = p[homeKey]?.let(::decodePlace),
            work = p[workKey]?.let(::decodePlace),
            recents = p[recentsKey]?.let(::decodeList).orEmpty(),
        )
    }.stateIn(scope, SharingStarted.Eagerly, SavedPlaces())

    fun setHome(place: Place?) = save(homeKey, place)
    fun setWork(place: Place?) = save(workKey, place)

    /** Most recent first, without duplicates (same place within 50 m), max [MAX_RECENTS]. */
    fun addRecent(place: Place) {
        scope.launch {
            store.edit { p ->
                val current = p[recentsKey]?.let(::decodeList).orEmpty()
                val updated = (listOf(place) + current.filterNot { Geo.distance(it.point, place.point) < 50.0 })
                    .take(MAX_RECENTS)
                p[recentsKey] = JSONArray(updated.map(::encodePlace)).toString()
            }
        }
    }

    fun clearRecents() {
        scope.launch { store.edit { it.remove(recentsKey) } }
    }

    private fun save(key: Preferences.Key<String>, place: Place?) {
        scope.launch {
            store.edit { if (place == null) it.remove(key) else it[key] = encodePlace(place).toString() }
        }
    }

    private fun encodePlace(p: Place) = JSONObject().apply {
        put("name", p.name)
        put("lat", p.point.lat)
        put("lon", p.point.lon)
        p.address?.let { put("address", it) }
        p.category?.let { put("category", it) }
    }

    private fun decodePlace(json: String): Place? = runCatching { decodePlace(JSONObject(json)) }.getOrNull()

    private fun decodePlace(o: JSONObject) = Place(
        name = o.getString("name"),
        point = GeoPoint(o.getDouble("lat"), o.getDouble("lon")),
        address = o.optString("address").takeIf { it.isNotEmpty() },
        category = o.optString("category").takeIf { it.isNotEmpty() },
    )

    private fun decodeList(json: String): List<Place> = runCatching {
        val arr = JSONArray(json)
        (0 until arr.length()).map { decodePlace(arr.getJSONObject(it)) }
    }.getOrDefault(emptyList())

    private companion object {
        const val MAX_RECENTS = 10
    }
}
