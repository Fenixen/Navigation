package cz.fenixen.navigation.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import cz.fenixen.navigation.core.geo.GeoPoint
import cz.fenixen.navigation.core.model.AvoidArea
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

private val Context.avoidStore: DataStore<Preferences> by preferencesDataStore(name = "avoids")

/** Persists closures (mainly the ones you reported) so they survive app restarts until they expire. */
class AvoidRepository(context: Context) {
    private val store = context.applicationContext.avoidStore
    private val key = stringPreferencesKey("areas")

    suspend fun load(): List<AvoidArea> = store.data.map { it[key]?.let(::decode).orEmpty() }.first()

    suspend fun save(areas: List<AvoidArea>) {
        store.edit { it[key] = encode(areas) }
    }

    private fun encode(areas: List<AvoidArea>): String = JSONArray(
        areas.map { a ->
            JSONObject().apply {
                put("id", a.id)
                put("exp", a.expiresAtMillis)
                put("src", a.source.name)
                a.label?.let { put("label", it) }
                put("pts", JSONArray(a.points.map { p -> JSONArray(listOf(p.lat, p.lon)) }))
            }
        },
    ).toString()

    private fun decode(json: String): List<AvoidArea> = runCatching {
        val arr = JSONArray(json)
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val pts = o.getJSONArray("pts")
            AvoidArea(
                id = o.getString("id"),
                points = (0 until pts.length()).map { j -> pts.getJSONArray(j).let { GeoPoint(it.getDouble(0), it.getDouble(1)) } },
                expiresAtMillis = o.getLong("exp"),
                source = AvoidArea.Source.valueOf(o.getString("src")),
                label = o.optString("label").takeIf { it.isNotEmpty() },
            )
        }
    }.getOrDefault(emptyList())
}
