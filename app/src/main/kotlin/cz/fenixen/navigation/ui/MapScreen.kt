package cz.fenixen.navigation.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cz.fenixen.navigation.R
import cz.fenixen.navigation.core.navigation.CameraState
import cz.fenixen.navigation.core.navigation.FollowMode
import cz.fenixen.navigation.core.navigation.NavigationState
import cz.fenixen.navigation.data.AppSettings
import cz.fenixen.navigation.data.SavedPlaces
import cz.fenixen.navigation.map.MapViewport
import cz.fenixen.navigation.map.NavMapController
import cz.fenixen.navigation.ui.components.ArrivedSheet
import cz.fenixen.navigation.ui.components.FailedSheet
import cz.fenixen.navigation.ui.components.FasterRouteCard
import cz.fenixen.navigation.ui.components.IncidentChip
import cz.fenixen.navigation.ui.components.GlassPanel
import cz.fenixen.navigation.ui.components.ManeuverCard
import cz.fenixen.navigation.ui.components.MapControls
import cz.fenixen.navigation.ui.components.NavMap
import cz.fenixen.navigation.ui.components.PlanningSheet
import cz.fenixen.navigation.ui.components.PrimaryButton
import cz.fenixen.navigation.ui.components.QuickPlaces
import cz.fenixen.navigation.ui.components.RecenterPill
import cz.fenixen.navigation.ui.components.RoutePreviewSheet
import cz.fenixen.navigation.ui.components.SearchBar
import cz.fenixen.navigation.ui.components.SearchOverlay
import cz.fenixen.navigation.ui.components.SpeedGauge
import cz.fenixen.navigation.ui.components.TripDock

@Composable
fun NavigationApp(
    vm: MainViewModel,
    dark: Boolean,
    hasLocationPermission: Boolean,
    onRequestPermission: () -> Unit,
    onMoveToBackground: () -> Unit,
) {
    val state by vm.navState.collectAsStateWithLifecycle()
    val screen by vm.screen.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val camera by vm.cameraState.collectAsStateWithLifecycle()
    val fix by vm.lastFix.collectAsStateWithLifecycle()
    val places by vm.places.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val search by vm.search.collectAsStateWithLifecycle()

    var controller by remember { mutableStateOf<NavMapController?>(null) }
    var viewport by remember { mutableStateOf(MapViewport()) }

    BackHandler {
        // Nejdřív zavírá vrstvy (hledání, náhled…); jinak appku jen schová – navigace běží dál.
        if (!vm.back()) onMoveToBackground()
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        // Na šířku / rozložený Fold: HUD ve sloupci vlevo, auto vycentrované ve zbytku mapy.
        val wide = maxWidth > maxHeight && maxWidth >= 600.dp
        NavMap(
            dark = dark,
            viewport = viewport,
            attributionBottomPx = viewport.bottom,
            modifier = Modifier.fillMaxSize(),
            onController = { controller = it },
        )
        val hud = HudCallbacks(vm, { controller }, onRequestPermission)
        if (wide) {
            WideHud(state, settings, camera, fix?.speedMps, places, hasLocationPermission, hud) { viewport = it }
        } else {
            PortraitHud(state, settings, camera, fix?.speedMps, places, hasLocationPermission, hud) { viewport = it }
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

/** Bundles the actions so both layouts stay small. */
private class HudCallbacks(val vm: MainViewModel, val controller: () -> NavMapController?, val requestPermission: () -> Unit)

@Composable
private fun PortraitHud(
    state: NavigationState,
    settings: AppSettings,
    camera: CameraState,
    speedMps: Double?,
    places: SavedPlaces,
    hasPermission: Boolean,
    hud: HudCallbacks,
    onViewport: (MapViewport) -> Unit,
) {
    val density = LocalDensity.current
    var topPx by remember { mutableIntStateOf(0) }
    var bottomPx by remember { mutableIntStateOf(0) }
    val margin = with(density) { 24.dp.roundToPx() }
    LaunchedEffect(topPx, bottomPx) { onViewport(MapViewport(top = topPx + margin, bottom = bottomPx + margin)) }

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp)
                .onGloballyPositioned { topPx = it.size.height + it.positionInRoot().y.toInt() },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) { TopContent(state, places, hasPermission, hud) }

        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().onSizeChanged { bottomPx = it.height },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            FasterRoute(state, hud, Modifier.padding(horizontal = 10.dp))
            Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                SpeedArea(state, speedMps, Modifier.align(Alignment.BottomStart))
                FollowArea(camera, hud, Modifier.align(Alignment.BottomCenter))
                Controls(camera, hud, state is NavigationState.Navigating, Modifier.align(Alignment.BottomEnd))
            }
            BottomContent(state, settings, hud, Modifier.navigationBarsPadding().padding(horizontal = 10.dp, vertical = 8.dp))
        }
    }
}

@Composable
private fun WideHud(
    state: NavigationState,
    settings: AppSettings,
    camera: CameraState,
    speedMps: Double?,
    places: SavedPlaces,
    hasPermission: Boolean,
    hud: HudCallbacks,
    onViewport: (MapViewport) -> Unit,
) {
    val density = LocalDensity.current
    val panel = 400.dp
    val left = with(density) { (panel + 28.dp).roundToPx() }
    val edge = with(density) { 24.dp.roundToPx() }
    LaunchedEffect(left, edge) { onViewport(MapViewport(left = left, top = edge, bottom = edge, right = edge)) }

    Box(Modifier.fillMaxSize().safeDrawingPadding().padding(12.dp)) {
        Column(Modifier.width(panel).fillMaxHeight()) {
            TopContent(state, places, hasPermission, hud)
            Spacer(Modifier.weight(1f))
            FasterRoute(state, hud, Modifier.padding(bottom = 8.dp))
            SpeedArea(state, speedMps, Modifier)
            Spacer(Modifier.height(8.dp))
            BottomContent(state, settings, hud, Modifier)
        }
        FollowArea(camera, hud, Modifier.align(Alignment.BottomCenter).padding(start = panel))
        Controls(camera, hud, state is NavigationState.Navigating, Modifier.align(Alignment.BottomEnd))
    }
}

@Composable
private fun TopContent(state: NavigationState, places: SavedPlaces, hasPermission: Boolean, hud: HudCallbacks) {
    when (state) {
        is NavigationState.Navigating -> Column(horizontalAlignment = Alignment.Start) {
            ManeuverCard(state)
            IncidentChip(state, Modifier.padding(top = 8.dp))
        }
        is NavigationState.Idle, is NavigationState.Failed -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
            SearchBar(onClick = hud.vm::openSearch, onSettings = hud.vm::openSettings)
            Spacer(Modifier.height(10.dp))
            QuickPlaces(places, onPlace = hud.vm::navigateTo, modifier = Modifier.fillMaxWidth())
            if (!hasPermission) {
                Spacer(Modifier.height(10.dp))
                GlassPanel(shape = RoundedCornerShape(24.dp)) {
                    Column(Modifier.padding(16.dp)) {
                        Text(stringResource(R.string.location_needed), style = MaterialTheme.typography.bodyLarge)
                        Spacer(Modifier.height(10.dp))
                        PrimaryButton(stringResource(R.string.grant), null, hud.requestPermission, Modifier.fillMaxWidth())
                    }
                }
            }
        }
        else -> Unit
    }
}

@Composable
private fun FasterRoute(state: NavigationState, hud: HudCallbacks, modifier: Modifier) {
    val offer = (state as? NavigationState.Navigating)?.fasterRoute
    AnimatedVisibility(offer != null, modifier, enter = slideInVertically { it } + fadeIn(), exit = slideOutVertically { it } + fadeOut()) {
        if (offer != null) FasterRouteCard(offer, onAccept = hud.vm::acceptFasterRoute, onDismiss = hud.vm::dismissFasterRoute)
    }
}

@Composable
private fun SpeedArea(state: NavigationState, speedMps: Double?, modifier: Modifier) {
    val navigating = state as? NavigationState.Navigating
    AnimatedVisibility(navigating != null || (speedMps ?: 0.0) > 1.0, modifier, enter = fadeIn() + scaleIn(), exit = fadeOut() + scaleOut()) {
        SpeedGauge(speedMps, navigating?.progress?.speedLimitKph, navigating?.isSpeeding == true)
    }
}

@Composable
private fun FollowArea(camera: CameraState, hud: HudCallbacks, modifier: Modifier) {
    AnimatedVisibility(
        camera.followMode != FollowMode.FOLLOW,
        modifier,
        enter = slideInVertically { it } + fadeIn(),
        exit = slideOutVertically { it } + fadeOut(),
    ) { RecenterPill(onClick = { hud.controller()?.recenter() }) }
}

@Composable
private fun Controls(camera: CameraState, hud: HudCallbacks, navigating: Boolean, modifier: Modifier) {
    val context = androidx.compose.ui.platform.LocalContext.current
    MapControls(
        camera = camera,
        onZoomIn = { hud.controller()?.zoomIn() },
        onZoomOut = { hud.controller()?.zoomOut() },
        onAutoZoom = { hud.controller()?.enableAutoZoom() },
        onLockCurrent = { hud.controller()?.lockCurrentZoom() },
        onLockMax = { hud.controller()?.lockMaxZoom() },
        onToggleOrientation = {
            hud.controller()?.toggleOrientation()
            hud.vm.setOrientationFromCamera()
        },
        modifier = modifier,
        onReportClosure = if (navigating) {
            {
                hud.vm.reportClosure()
                android.widget.Toast.makeText(context, "Úsek před vámi zablokován, hledám objížďku", android.widget.Toast.LENGTH_SHORT).show()
            }
        } else {
            null
        },
    )
}

@Composable
private fun BottomContent(state: NavigationState, settings: AppSettings, hud: HudCallbacks, modifier: Modifier) {
    val vm = hud.vm
    when (state) {
        is NavigationState.Navigating -> TripDock(
            state = state,
            voiceEnabled = settings.voiceEnabled,
            onStop = vm::stopNavigation,
            onOverview = { hud.controller()?.showOverview() },
            onToggleVoice = vm::toggleVoice,
            modifier = modifier,
        )
        is NavigationState.Preview -> RoutePreviewSheet(
            state = state,
            settings = settings,
            onSelect = vm::selectRoute,
            onStart = vm::startNavigation,
            onCancel = vm::stopNavigation,
            onAvoidTolls = { vm.setAvoidance(tolls = it) },
            onAvoidHighways = { vm.setAvoidance(highways = it) },
        )
        is NavigationState.Planning -> PlanningSheet(state.destination, onCancel = vm::stopNavigation)
        is NavigationState.Failed -> FailedSheet(state, onRetry = vm::retry, onCancel = vm::stopNavigation)
        is NavigationState.Arrived -> ArrivedSheet(state.destination, onDone = vm::stopNavigation)
        NavigationState.Idle -> Spacer(Modifier.navigationBarsPadding())
    }
}
