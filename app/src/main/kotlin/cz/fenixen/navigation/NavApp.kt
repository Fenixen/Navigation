package cz.fenixen.navigation

import android.app.Application
import android.content.Context
import android.util.Log
import org.maplibre.android.MapLibre
import org.maplibre.android.offline.OfflineManager

class NavApp : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        // MapLibre bez API klíče – dlaždice z OpenFreeMap (OSM data, bez limitů).
        MapLibre.getInstance(this)
        // Větší cache dlaždic (výchozí ~50 MB): často projížděné oblasti fungují i bez signálu.
        OfflineManager.getInstance(this).setMaximumAmbientCacheSize(
            TILE_CACHE_BYTES,
            object : OfflineManager.FileSourceCallback {
                override fun onSuccess() = Unit
                override fun onError(message: String) {
                    Log.w("NavApp", "Tile cache resize failed: $message")
                }
            },
        )
        graph = AppGraph(this)
    }

    private companion object {
        const val TILE_CACHE_BYTES = 512L * 1024 * 1024
    }
}

val Context.appGraph: AppGraph get() = (applicationContext as NavApp).graph
