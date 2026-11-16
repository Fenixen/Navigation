package cz.fenixen.navigation.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import cz.fenixen.navigation.core.navigation.MapOrientation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class MapTheme { AUTO, DAY, NIGHT }

/** Which screen a camera belongs to – phone and car keep their own zoom lock (different DPI/size). */
enum class DisplayKind { PHONE, CAR }

data class AppSettings(
    val phoneZoomLock: Double? = null,
    val carZoomLock: Double? = null,
    val orientation: MapOrientation = MapOrientation.HEADING_UP,
    val avoidTolls: Boolean = false,
    val avoidHighways: Boolean = false,
    val avoidFerries: Boolean = false,
    val voiceEnabled: Boolean = true,
    val routingUrl: String = DEFAULT_ROUTING_URL,
    val geocoderUrl: String = DEFAULT_GEOCODER_URL,
    val mapTheme: MapTheme = MapTheme.AUTO,
    val simulateDriving: Boolean = false,
) {
    fun zoomLock(display: DisplayKind) = if (display == DisplayKind.PHONE) phoneZoomLock else carZoomLock

    companion object {
        /** Public FOSSGIS Valhalla – fine for testing; for daily use run your own (e.g. on the Pi). */
        const val DEFAULT_ROUTING_URL = "https://valhalla1.openstreetmap.de"
        const val DEFAULT_GEOCODER_URL = "https://photon.komoot.io"
    }
}

private val Context.settingsStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/**
 * Settings persisted with Jetpack DataStore (async, transactional – unlike SharedPreferences
 * it never does disk I/O on the main thread).
 */
class SettingsRepository(context: Context, private val scope: CoroutineScope) {
    private val store = context.applicationContext.settingsStore

    private object Keys {
        val phoneZoom = doublePreferencesKey("phone_zoom_lock")
        val carZoom = doublePreferencesKey("car_zoom_lock")
        val orientation = stringPreferencesKey("orientation")
        val avoidTolls = booleanPreferencesKey("avoid_tolls")
        val avoidHighways = booleanPreferencesKey("avoid_highways")
        val avoidFerries = booleanPreferencesKey("avoid_ferries")
        val voice = booleanPreferencesKey("voice")
        val routingUrl = stringPreferencesKey("routing_url")
        val geocoderUrl = stringPreferencesKey("geocoder_url")
        val theme = stringPreferencesKey("map_theme")
        val simulate = booleanPreferencesKey("simulate")
    }

    val settings: Flow<AppSettings> = store.data.map { p ->
        AppSettings(
            phoneZoomLock = p[Keys.phoneZoom],
            carZoomLock = p[Keys.carZoom],
            orientation = p[Keys.orientation]?.let { runCatching { MapOrientation.valueOf(it) }.getOrNull() }
                ?: MapOrientation.HEADING_UP,
            avoidTolls = p[Keys.avoidTolls] ?: false,
            avoidHighways = p[Keys.avoidHighways] ?: false,
            avoidFerries = p[Keys.avoidFerries] ?: false,
            voiceEnabled = p[Keys.voice] ?: true,
            routingUrl = p[Keys.routingUrl]?.takeIf { it.isNotBlank() } ?: AppSettings.DEFAULT_ROUTING_URL,
            geocoderUrl = p[Keys.geocoderUrl]?.takeIf { it.isNotBlank() } ?: AppSettings.DEFAULT_GEOCODER_URL,
            mapTheme = p[Keys.theme]?.let { runCatching { MapTheme.valueOf(it) }.getOrNull() } ?: MapTheme.AUTO,
            simulateDriving = p[Keys.simulate] ?: false,
        )
    }

    /** Always-available snapshot (defaults until the first disk read completes – a few ms). */
    val current: StateFlow<AppSettings> = settings.stateIn(scope, SharingStarted.Eagerly, AppSettings())

    suspend fun load(): AppSettings = settings.first()

    fun setZoomLock(display: DisplayKind, zoom: Double?) = edit {
        val key = if (display == DisplayKind.PHONE) Keys.phoneZoom else Keys.carZoom
        if (zoom == null) it.remove(key) else it[key] = zoom
    }

    fun setOrientation(o: MapOrientation) = edit { it[Keys.orientation] = o.name }
    fun setAvoidTolls(v: Boolean) = edit { it[Keys.avoidTolls] = v }
    fun setAvoidHighways(v: Boolean) = edit { it[Keys.avoidHighways] = v }
    fun setAvoidFerries(v: Boolean) = edit { it[Keys.avoidFerries] = v }
    fun setVoiceEnabled(v: Boolean) = edit { it[Keys.voice] = v }
    fun setRoutingUrl(v: String) = edit { it[Keys.routingUrl] = v.trim() }
    fun setGeocoderUrl(v: String) = edit { it[Keys.geocoderUrl] = v.trim() }
    fun setMapTheme(v: MapTheme) = edit { it[Keys.theme] = v.name }
    fun setSimulateDriving(v: Boolean) = edit { it[Keys.simulate] = v }

    private fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        scope.launch { store.edit { block(it) } }
    }
}
