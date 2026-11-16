package cz.fenixen.navigation.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cz.fenixen.navigation.R
import cz.fenixen.navigation.core.navigation.NavigationState
import cz.fenixen.navigation.map.MapViewport
import cz.fenixen.navigation.map.NavMapController
import cz.fenixen.navigation.ui.components.ArrivedSheet
import cz.fenixen.navigation.ui.components.FailedSheet
import cz.fenixen.navigation.ui.components.ManeuverBanner
import cz.fenixen.navigation.ui.components.MapControls
import cz.fenixen.navigation.ui.components.NavMap
import cz.fenixen.navigation.ui.components.PlanningSheet
import cz.fenixen.navigation.ui.components.QuickPlaces
import cz.fenixen.navigation.ui.components.RoutePreviewSheet
import cz.fenixen.navigation.ui.components.SearchBar
import cz.fenixen.navigation.ui.components.SearchOverlay
import cz.fenixen.navigation.ui.components.SpeedIndicator
import cz.fenixen.navigation.ui.components.TripBar

@Composable
fun NavigationApp(vm: MainViewModel, dark: Boolean, hasLocationPermission: Boolean, onRequestPermission: () -> Unit) {
    val state by vm.navState.collectAsStateWithLifecycle()
    val screen by vm.screen.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val camera by vm.cameraState.collectAsStateWithLifecycle()
    val fix by vm.lastFix.collectAsStateWithLifecycle()
    val places by vm.places.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val search by vm.search.collectAsStateWithLifecycle()

    var controller by remember { mutableStateOf<NavMapController?>(null) }
    var topPx by remember { mutableIntStateOf(0) }
    var bottomPx by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current

    BackHandler(enabled = screen != Screen.MAP || state !is NavigationState.Idle) { vm.back() }

    Box(Modifier.fillMaxSize()) {
        NavMap(
            dark = dark,
            viewport = MapViewport(top = topPx, bottom = bottomPx),
            attributionBottomPx = bottomPx,
            modifier = Modifier.fillMaxSize(),
            onController = { controller = it },
        )

        // ---------- horní část
        Column(
            Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .onSizeChanged { topPx = it.height + with(density) { 40.dp.roundToPx() } },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            when (val s = state) {
                is NavigationState.Navigating -> ManeuverBanner(s, dark)
                is NavigationState.Idle, is NavigationState.Failed -> {
                    SearchBar(onClick = vm::openSearch, onSettings = vm::openSettings)
                    Spacer(Modifier.height(10.dp))
                    QuickPlaces(places, onPlace = vm::navigateTo, modifier = Modifier.fillMaxWidth())
                    if (!hasLocationPermission) {
                        Spacer(Modifier.height(10.dp))
                        PermissionCard(onRequestPermission)
                    }
                }
                else -> Unit
            }
        }

        // ---------- spodní část
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .onSizeChanged { bottomPx = it.height },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val navigating = state as? NavigationState.Navigating
            Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                if (navigating != null || (fix?.speedMps ?: 0.0) > 1.0) {
                    SpeedIndicator(
                        speedMps = fix?.speedMps,
                        limitKph = navigating?.progress?.speedLimitKph,
                        speeding = navigating?.isSpeeding == true,
                        modifier = Modifier.align(Alignment.BottomStart),
                    )
                }
                MapControls(
                    camera = camera,
                    onZoomIn = { controller?.zoomIn() },
                    onZoomOut = { controller?.zoomOut() },
                    onAutoZoom = { controller?.enableAutoZoom() },
                    onLockCurrent = { controller?.lockCurrentZoom() },
                    onLockMax = { controller?.lockMaxZoom() },
                    onRecenter = { controller?.recenter() },
                    onToggleOrientation = {
                        controller?.toggleOrientation()
                        vm.setOrientationFromCamera()
                    },
                    modifier = Modifier.align(Alignment.BottomEnd),
                )
            }
            when (val s = state) {
                is NavigationState.Navigating -> TripBar(
                    state = s,
                    voiceEnabled = settings.voiceEnabled,
                    onStop = vm::stopNavigation,
                    onOverview = { controller?.showOverview() },
                    onToggleVoice = vm::toggleVoice,
                    modifier = Modifier.navigationBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp),
                )
                is NavigationState.Preview -> RoutePreviewSheet(
                    state = s,
                    settings = settings,
                    onSelect = vm::selectRoute,
                    onStart = vm::startNavigation,
                    onCancel = vm::stopNavigation,
                    onAvoidTolls = { vm.setAvoidance(tolls = it) },
                    onAvoidHighways = { vm.setAvoidance(highways = it) },
                )
                is NavigationState.Planning -> PlanningSheet(s.destination, onCancel = vm::stopNavigation)
                is NavigationState.Failed -> FailedSheet(s, onRetry = vm::retry, onCancel = vm::stopNavigation)
                is NavigationState.Arrived -> ArrivedSheet(s.destination, onDone = vm::stopNavigation)
                NavigationState.Idle -> Spacer(Modifier.windowInsetsPadding(WindowInsets.safeDrawing).height(0.dp))
            }
        }

        when (screen) {
            Screen.SEARCH -> SearchOverlay(
                query = query,
                state = search,
                saved = places,
                myLocation = fix?.point,
                onQueryChange = vm::onQueryChange,
                onBack = { vm.back() },
                onPlace = vm::navigateTo,
                onSetHome = vm::setHome,
                onSetWork = vm::setWork,
            )
            Screen.SETTINGS -> SettingsScreen(
                settings = settings,
                vm = vm,
                onToggleOrientation = {
                    controller?.toggleOrientation()
                    vm.setOrientationFromCamera()
                },
                onBack = { vm.back() },
            )
            Screen.MAP -> Unit
        }
    }
}

@Composable
private fun PermissionCard(onRequest: () -> Unit) {
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.errorContainer) {
        Column(Modifier.padding(16.dp)) {
            Text(stringResource(R.string.location_needed), style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(8.dp))
            Button(onClick = onRequest) { Text(stringResource(R.string.grant)) }
        }
    }
}
