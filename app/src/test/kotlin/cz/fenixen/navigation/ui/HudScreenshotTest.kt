package cz.fenixen.navigation.ui

import android.app.Application
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import cz.fenixen.navigation.core.geo.GeoPoint
import cz.fenixen.navigation.core.geo.Polyline
import cz.fenixen.navigation.core.model.Maneuver
import cz.fenixen.navigation.core.model.ManeuverSign
import cz.fenixen.navigation.core.model.ManeuverType
import cz.fenixen.navigation.core.model.Place
import cz.fenixen.navigation.core.model.Route
import cz.fenixen.navigation.core.model.IncidentCategory
import cz.fenixen.navigation.core.model.RouteIncident
import cz.fenixen.navigation.core.model.RouteTraffic
import cz.fenixen.navigation.core.model.TrafficIncident
import cz.fenixen.navigation.core.navigation.FasterRouteOffer
import cz.fenixen.navigation.ui.components.FasterRouteCard
import cz.fenixen.navigation.ui.components.IncidentChip
import cz.fenixen.navigation.core.navigation.CameraState
import cz.fenixen.navigation.core.navigation.FollowMode
import cz.fenixen.navigation.core.navigation.NavigationState
import cz.fenixen.navigation.core.navigation.RouteProgress
import cz.fenixen.navigation.core.navigation.ZoomPolicy
import cz.fenixen.navigation.data.AppSettings
import cz.fenixen.navigation.data.SavedPlaces
import cz.fenixen.navigation.map.MapPalette
import cz.fenixen.navigation.map.PuckBitmaps
import cz.fenixen.navigation.ui.components.ManeuverCard
import cz.fenixen.navigation.ui.components.ManeuverIcon
import cz.fenixen.navigation.ui.components.MapControls
import cz.fenixen.navigation.ui.components.QuickPlaces
import cz.fenixen.navigation.ui.components.RecenterPill
import cz.fenixen.navigation.ui.components.RoutePreviewSheet
import cz.fenixen.navigation.ui.components.SearchBar
import cz.fenixen.navigation.ui.components.SpeedGauge
import cz.fenixen.navigation.ui.components.TripDock
import cz.fenixen.navigation.ui.theme.Hud
import cz.fenixen.navigation.ui.theme.NavTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders the real HUD composables (and the real pickup bitmap) over a stylised map that uses
 * the app's own map palette. MapLibre itself needs a GPU, which Robolectric doesn't have.
 * Output: app/build/outputs/roborazzi/… PNGs.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w400dp-h860dp-xxhdpi", application = Application::class)
class HudScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private val out = "build/outputs/roborazzi"

    private fun maneuver(type: ManeuverType, text: String, street: String? = null, at: Double, len: Double, sign: ManeuverSign? = null, exits: Int? = null) =
        Maneuver(
            type = type, instruction = text, streetNames = listOfNotNull(street), beginShapeIndex = 0,
            distanceFromStart = at, lengthMeters = len, durationSeconds = len / 12, sign = sign, roundaboutExitCount = exits,
        )

    private val geometry = Polyline(listOf(GeoPoint(49.19, 16.61), GeoPoint(49.30, 16.62)))

    private fun navigating(
        previous: Maneuver,
        upcoming: Maneuver,
        following: Maneuver?,
        toUpcoming: Double,
        speed: Double,
        limit: Int?,
        speeding: Boolean,
        delay: Double? = null,
        incident: IncidentCategory? = null,
        offer: Boolean = false,
    ): NavigationState.Navigating {
        val ms = listOfNotNull(previous, upcoming, following, maneuver(ManeuverType.ARRIVE, "Cíl", at = 12_000.0, len = 0.0))
        val traffic = delay?.let { RouteTraffic(0.0, 1_460.0, it, emptyList(), 1) }
        val route = Route("r", geometry, ms, 18_400.0, 1_460.0, "D1", traffic = traffic)
        val progress = RouteProgress(
            distanceAlong = 4_200.0, distanceRemaining = 14_200.0, durationRemaining = 1_260.0, upcomingManeuverIndex = 1,
            distanceToUpcoming = toUpcoming, durationToUpcoming = 20.0, snappedPoint = geometry.points[0], snappedBearing = 0.0,
            distanceFromRoute = 0.0, speedLimitKph = limit,
        )
        val incidents = incident?.let { listOf(RouteIncident(TrafficIncident("i1", it, listOf(geometry.points[0]), delaySeconds = 540.0), 6_000.0)) }.orEmpty()
        val fasterRoute = if (offer) FasterRouteOffer(1, route.copy(summary = "D52, I/53"), 480.0, 0) else null
        return NavigationState.Navigating(
            Place("Hlavní nádraží", GeoPoint(49.19, 16.61)), route, progress, speed / 3.6, speeding,
            incidents = incidents, fasterRoute = fasterRoute,
        )
    }

    /** Stylised map in the app's palette: blocks, roads, motorway, glowing route, jam, maneuver arrow, pickup. */
    @Composable
    private fun FakeMap(dark: Boolean, carAt: Float = 0.70f, jam: Boolean = false) {
        val p = if (dark) MapPalette.NIGHT else MapPalette.DAY
        val bg = Color(p.background)
        val road = Color(p.minorRoad)
        val major = Color(p.majorRoad)
        val motorway = Color(p.motorway)
        val water = Color(p.water)
        val green = Color(p.green)
        val pickup = PuckBitmaps.pickup(LocalContext.current, 30f).asImageBitmap()
        BoxWithConstraints(Modifier.fillMaxSize().background(bg)) {
            val w = maxWidth
            val h = maxHeight
            Canvas(Modifier.fillMaxSize()) {
                drawRect(green, Offset(size.width * 0.05f, size.height * 0.30f), androidx.compose.ui.geometry.Size(size.width * 0.28f, size.height * 0.14f))
                drawRect(water, Offset(size.width * 0.70f, size.height * 0.52f), androidx.compose.ui.geometry.Size(size.width * 0.4f, size.height * 0.08f))
                for (i in -2..14) {
                    val x = size.width * i / 7f
                    drawLine(road, Offset(x, 0f), Offset(x + 160f, size.height), strokeWidth = 18f)
                    val y = size.height * i / 11f
                    drawLine(road, Offset(0f, y), Offset(size.width, y - 120f), strokeWidth = 16f)
                }
                drawLine(major, Offset(0f, size.height * 0.48f), Offset(size.width, size.height * 0.40f), strokeWidth = 30f)
                drawLine(motorway, Offset(size.width * 0.82f, 0f), Offset(size.width * 0.95f, size.height), strokeWidth = 38f)
                val route = Path().apply {
                    moveTo(size.width * 0.5f, size.height * (carAt + 0.02f))
                    lineTo(size.width * 0.505f, size.height * 0.50f)
                    lineTo(size.width * 0.51f, size.height * 0.44f)
                    lineTo(size.width * 0.86f, size.height * 0.385f)
                    lineTo(size.width * 1.05f, size.height * 0.36f)
                }
                val blue = Color(0xFF2F7BFF)
                drawPath(route, blue.copy(alpha = if (dark) 0.45f else 0.22f), style = Stroke(width = 80f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                drawPath(route, if (dark) Color.White else Color(0xFF0A2E7A), style = Stroke(width = 44f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                drawPath(route, blue, style = Stroke(width = 32f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                if (jam) {
                    drawLine(Color(0xFFE8253C), Offset(size.width * 0.62f, size.height * 0.4225f), Offset(size.width * 0.80f, size.height * 0.394f), strokeWidth = 32f, cap = StrokeCap.Round)
                    drawLine(Color(0xFFFFC233), Offset(size.width * 0.52f, size.height * 0.438f), Offset(size.width * 0.62f, size.height * 0.4225f), strokeWidth = 32f, cap = StrokeCap.Round)
                }
                // Šipka manévru v křižovatce
                val arrow = Path().apply {
                    moveTo(size.width * 0.505f, size.height * 0.50f)
                    lineTo(size.width * 0.51f, size.height * 0.44f)
                    lineTo(size.width * 0.60f, size.height * 0.425f)
                }
                drawPath(arrow, Color(0xFF0A2E7A), style = Stroke(width = 30f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                drawPath(arrow, Color.White, style = Stroke(width = 16f, join = StrokeJoin.Round))
            }
            Image(
                pickup,
                null,
                Modifier.offset(w * 0.5f - 16.dp, h * carAt - 30.dp).size(32.dp, 62.dp),
            )
        }
    }

    @Composable
    private fun NavScene(state: NavigationState.Navigating, dark: Boolean, camera: CameraState, jam: Boolean = false) {
        NavTheme(dark) {
            Box(Modifier.fillMaxSize()) {
                FakeMap(dark, jam = jam)
                Column(Modifier.align(Alignment.TopCenter).padding(12.dp).padding(top = 28.dp)) {
                    ManeuverCard(state)
                    IncidentChip(state, Modifier.padding(top = 8.dp))
                }
                Column(Modifier.align(Alignment.BottomCenter)) {
                    state.fasterRoute?.let { FasterRouteCard(it, {}, {}, Modifier.padding(horizontal = 10.dp)) }
                    Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                        SpeedGauge(state.speedMps, state.progress?.speedLimitKph, state.isSpeeding, Modifier.align(Alignment.BottomStart))
                        if (camera.followMode != FollowMode.FOLLOW) RecenterPill({}, Modifier.align(Alignment.BottomCenter))
                        MapControls(camera, {}, {}, {}, {}, {}, {}, Modifier.align(Alignment.BottomEnd))
                    }
                    TripDock(state, voiceEnabled = true, onStop = {}, onOverview = {}, onToggleVoice = {}, modifier = Modifier.padding(10.dp))
                }
            }
        }
    }

    @Test
    fun navigatingCityDay() {
        val prev = maneuver(ManeuverType.CONTINUE, "Pokračujte", "Kounicova", at = 3_900.0, len = 400.0)
        val turn = maneuver(ManeuverType.RIGHT, "Odbočte vpravo na Husova.", "Husova", at = 4_300.0, len = 120.0)
        val then = maneuver(ManeuverType.LEFT, "Odbočte vlevo na Joštova.", "Joštova", at = 4_420.0, len = 800.0)
        val camera = CameraState(followMode = FollowMode.FOLLOW, zoomPolicy = ZoomPolicy.Locked(20.5), effectiveZoom = 20.5)
        compose.setContent { NavScene(navigating(prev, turn, then, 120.0, speed = 57.0, limit = 50, speeding = true), dark = false, camera) }
        compose.onRoot().captureRoboImage("$out/1_navigace_mesto_den.png")
    }

    @Test
    fun navigatingHighwayNightWithTraffic() {
        val prev = maneuver(ManeuverType.RAMP_STRAIGHT, "Nájezd", "D1", at = 1_200.0, len = 4_500.0)
        val exit = maneuver(
            ManeuverType.EXIT_RIGHT, "Sjeďte na výjezdu 230 na 46 směrem na Olomouc/Ostrava.", "D46", at = 5_700.0, len = 37_000.0,
            sign = ManeuverSign(exitNumber = "230", toward = listOf("Olomouc", "Ostrava")),
        )
        val camera = CameraState(followMode = FollowMode.FREE, zoomPolicy = ZoomPolicy.Auto, effectiveZoom = 15.0)
        compose.setContent {
            NavScene(
                navigating(prev, exit, null, 1_500.0, speed = 128.0, limit = 130, speeding = false, delay = 420.0, incident = IncidentCategory.ACCIDENT, offer = true),
                dark = true, camera, jam = true,
            )
        }
        compose.onRoot().captureRoboImage("$out/2_navigace_dalnice_noc.png")
    }

    @Test
    fun routePreview() {
        val ms = listOf(maneuver(ManeuverType.DEPART, "Jeďte", at = 0.0, len = 10.0), maneuver(ManeuverType.ARRIVE, "Cíl", at = 10.0, len = 0.0))
        val routes = listOf(
            Route("a", geometry, ms, 76_800.0, 3_552.0, "D1, D46", hasToll = true, hasHighway = true, traffic = RouteTraffic(0.0, 3_120.0, 240.0, emptyList(), 1)),
            Route("b", geometry, ms, 71_200.0, 4_380.0, "I/46, II/150", traffic = RouteTraffic(0.0, 3_960.0, 0.0, emptyList(), 1)),
        )
        val state = NavigationState.Preview(Place("Olomouc", GeoPoint(49.59, 17.25), "Horní náměstí, Olomouc"), routes, 0)
        compose.setContent {
            NavTheme(false) {
                Box(Modifier.fillMaxSize()) {
                    FakeMap(false, carAt = 0.5f)
                    RoutePreviewSheet(state, AppSettings(), {}, {}, {}, {}, {}, Modifier.align(Alignment.BottomCenter))
                }
            }
        }
        compose.onRoot().captureRoboImage("$out/3_nahled_trasy.png")
    }

    @Test
    fun idleWithSearch() {
        val saved = SavedPlaces(
            home = Place("Domov", GeoPoint(49.2, 16.6)),
            work = Place("Mendelu", GeoPoint(49.21, 16.61)),
            recents = listOf(Place("Lysá hora", GeoPoint(49.54, 18.45))),
        )
        compose.setContent {
            NavTheme(true) {
                Box(Modifier.fillMaxSize()) {
                    FakeMap(true, carAt = 0.62f)
                    Column(Modifier.padding(12.dp).padding(top = 28.dp)) {
                        SearchBar({}, {})
                        Spacer(Modifier.height(10.dp))
                        QuickPlaces(saved, {})
                    }
                    MapControls(CameraState(), {}, {}, {}, {}, {}, {}, Modifier.align(Alignment.BottomEnd).padding(12.dp).padding(bottom = 24.dp))
                }
            }
        }
        compose.onRoot().captureRoboImage("$out/4_vychozi_obrazovka.png")
    }

    @Test
    fun maneuverIconSheet() {
        compose.setContent {
            NavTheme(true) {
                Column(Modifier.background(Hud.Background).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val pickup = PuckBitmaps.pickup(LocalContext.current, 60f).asImageBitmap()
                        Image(pickup, null, Modifier.size(64.dp, 124.dp))
                        Spacer(Modifier.width(16.dp))
                        Image(pickup, null, Modifier.size(64.dp, 124.dp).rotate(35f))
                        Spacer(Modifier.width(16.dp))
                        Text("Ukazatel polohy", color = Hud.TextPrimary, style = MaterialTheme.typography.titleLarge)
                    }
                    ManeuverType.entries.filter { it != ManeuverType.UNKNOWN }.chunked(4).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            row.forEach { type ->
                                Column(Modifier.size(width = 92.dp, height = 86.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                    Box(Modifier.size(56.dp).background(Hud.PapayaGradient, androidx.compose.foundation.shape.RoundedCornerShape(16.dp)), contentAlignment = Alignment.Center) {
                                        ManeuverIcon(maneuver(type, type.name, at = 0.0, len = 0.0, exits = 3), 44.dp)
                                    }
                                    Text(type.name.lowercase(), color = Hud.TextSecondary, style = MaterialTheme.typography.labelMedium, maxLines = 1)
                                }
                            }
                        }
                    }
                }
            }
        }
        compose.onRoot().captureRoboImage("$out/5_ikony_manevru.png")
    }
}
