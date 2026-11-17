package cz.fenixen.navigation.ui.components

import android.view.Gravity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import cz.fenixen.navigation.appGraph
import cz.fenixen.navigation.map.MapViewport
import cz.fenixen.navigation.map.NavMapController
import kotlinx.coroutines.flow.MutableStateFlow
import org.maplibre.android.maps.MapView

/**
 * MapLibre MapView inside Compose. MapView is a classic View with its own lifecycle
 * (GL context, tile cache), so we forward Activity lifecycle events to it manually.
 */
@Composable
fun NavMap(
    dark: Boolean,
    viewport: MapViewport,
    attributionBottomPx: Int,
    modifier: Modifier = Modifier,
    onController: (NavMapController) -> Unit,
) {
    val context = LocalContext.current
    val graph = context.appGraph
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val darkFlow = remember { MutableStateFlow(dark) }
    val density = LocalDensity.current

    val mapView = remember { MapView(context).apply { onCreate(null) } }
    val controller = remember {
        NavMapController(context, mapView, graph.phoneCamera, graph.session, scope, darkFlow, graph.trafficTilesKey).also {
            it.start()
            onController(it)
        }
    }

    LaunchedEffect(dark) { darkFlow.value = dark }
    LaunchedEffect(viewport) { controller.viewport = viewport }
    LaunchedEffect(attributionBottomPx) {
        mapView.getMapAsync { m ->
            val margin = with(density) { 12.dp.roundToPx() }
            m.uiSettings.attributionGravity = Gravity.BOTTOM or Gravity.START
            m.uiSettings.setAttributionMargins(margin, 0, 0, attributionBottomPx + margin)
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            controller.release()
            mapView.onDestroy()
        }
    }

    AndroidView(factory = { mapView }, modifier = modifier)
}
