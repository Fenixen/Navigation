package cz.fenixen.navigation.ui

import android.app.Application
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
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
import cz.fenixen.navigation.core.navigation.CameraState
import cz.fenixen.navigation.core.navigation.FollowMode
import cz.fenixen.navigation.core.navigation.NavigationState
import cz.fenixen.navigation.core.navigation.RouteProgress
import cz.fenixen.navigation.core.navigation.ZoomPolicy
import cz.fenixen.navigation.data.AppSettings
import cz.fenixen.navigation.data.SavedPlaces
import cz.fenixen.navigation.ui.components.ManeuverBanner
import cz.fenixen.navigation.ui.components.ManeuverIcon
import cz.fenixen.navigation.ui.components.MapControls
import cz.fenixen.navigation.ui.components.QuickPlaces
import cz.fenixen.navigation.ui.components.RoutePreviewSheet
import cz.fenixen.navigation.ui.components.SearchBar
import cz.fenixen.navigation.ui.components.SpeedIndicator
import cz.fenixen.navigation.ui.components.TripBar
import cz.fenixen.navigation.ui.theme.NavTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders the real HUD composables on top of a stylised fake map (MapLibre needs a GPU,
 * which Robolectric doesn't have). Output: app/build/outputs/roborazzi/… PNGs.
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

    private val geometry = Polyline(listOf(GeoPoint(49.19, 16.61), GeoPoint(49.20, 16.62)))

    private fun navigating(upcoming: Maneuver, following: Maneuver?, toUpcoming: Double, speed: Double, limit: Int?, speeding: Boolean): NavigationState.Navigating {
        val ms = listOfNotNull(maneuver(ManeuverType.DEPART, "Jeďte", at = 0.0, len = 100.0), upcoming, following,
            maneuver(ManeuverType.ARRIVE, "Cíl", at = 9_000.0, len = 0.0))
        val route = Route("r", geometry, ms, 18_400.0, 1_460.0, "D1")
        val progress = RouteProgress(
            distanceAlong = 1_000.0, distanceRemaining = 18_400.0, durationRemaining = 1_460.0, upcomingManeuverIndex = 1,
            distanceToUpcoming = toUpcoming, durationToUpcoming = 20.0, snappedPoint = geometry.points[0], snappedBearing = 0.0,
            distanceFromRoute = 0.0, speedLimitKph = limit,
        )
        return NavigationState.Navigating(Place("Hlavní nádraží", GeoPoint(49.19, 16.61)), route, progress, speed / 3.6, speeding)
    }

    @Composable
    private fun FakeMap(dark: Boolean) {
        val land = if (dark) Color(0xFF1C2128) else Color(0xFFEDEAE3)
        val road = if (dark) Color(0xFF3A414C) else Color.White
        Canvas(Modifier.fillMaxSize().background(land)) {
            // Ulice v mřížce + projetá (šedá) a zbývající (modrá) část trasy – jen ilustrace mapy.
            for (i in 0..12) {
                val x = size.width * i / 8f - 60f
                drawLine(road, Offset(x, 0f), Offset(x + 220f, size.height), strokeWidth = if (i % 4 == 0) 34f else 18f)
                val y = size.height * i / 10f
                drawLine(road, Offset(0f, y), Offset(size.width, y - 160f), strokeWidth = if (i % 3 == 0) 30f else 16f)
            }
            val path = Path().apply {
                moveTo(size.width * 0.5f, size.height * 1.05f)
                lineTo(size.width * 0.52f, size.height * 0.62f)
                lineTo(size.width * 0.55f, size.height * 0.42f)
                lineTo(size.width * 0.85f, size.height * 0.36f)
            }
            drawPath(path, Color(0xFF1A3E8C), style = Stroke(width = 46f, cap = StrokeCap.Round, join = StrokeJoin.Round))
            drawPath(path, Color(0xFF2F6FED), style = Stroke(width = 32f, cap = StrokeCap.Round, join = StrokeJoin.Round))
            drawCircle(Color.White, 26f, Offset(size.width * 0.52f, size.height * 0.66f))
            drawCircle(Color(0xFF2F6FED), 19f, Offset(size.width * 0.52f, size.height * 0.66f))
        }
    }

    @Composable
    private fun NavScene(state: NavigationState.Navigating, dark: Boolean, camera: CameraState) {
        NavTheme(dark) {
            Box(Modifier.fillMaxSize()) {
                FakeMap(dark)
                ManeuverBanner(state, dark, Modifier.align(Alignment.TopCenter).padding(12.dp).padding(top = 28.dp))
                Column(Modifier.align(Alignment.BottomCenter)) {
                    Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                        SpeedIndicator(state.speedMps, state.progress?.speedLimitKph, state.isSpeeding, Modifier.align(Alignment.BottomStart))
                        MapControls(camera, {}, {}, {}, {}, {}, {}, {}, Modifier.align(Alignment.BottomEnd))
                    }
                    TripBar(state, voiceEnabled = true, onStop = {}, onOverview = {}, onToggleVoice = {}, modifier = Modifier.padding(12.dp))
                }
            }
        }
    }

    @Test
    fun navigatingCityDay() {
        val turn = maneuver(ManeuverType.RIGHT, "Odbočte vpravo na Husova.", "Husova", at = 1_300.0, len = 120.0)
        val then = maneuver(ManeuverType.LEFT, "Odbočte vlevo na Joštova.", "Joštova", at = 1_420.0, len = 800.0)
        val camera = CameraState(followMode = FollowMode.FOLLOW, zoomPolicy = ZoomPolicy.Locked(20.5), effectiveZoom = 20.5)
        compose.setContent { NavScene(navigating(turn, then, 300.0, speed = 57.0, limit = 50, speeding = true), dark = false, camera) }
        compose.onRoot().captureRoboImage("$out/1_navigace_mesto_den.png")
    }

    @Test
    fun navigatingHighwayNight() {
        val exit = maneuver(
            ManeuverType.EXIT_RIGHT, "Sjeďte na výjezdu 230 na 46 směrem na Olomouc/Ostrava.", "D46", at = 2_500.0, len = 37_000.0,
            sign = ManeuverSign(exitNumber = "230", toward = listOf("Olomouc", "Ostrava")),
        )
        val camera = CameraState(followMode = FollowMode.FREE, zoomPolicy = ZoomPolicy.Auto, effectiveZoom = 15.0)
        compose.setContent { NavScene(navigating(exit, null, 1_500.0, speed = 128.0, limit = 130, speeding = false), dark = true, camera) }
        compose.onRoot().captureRoboImage("$out/2_navigace_dalnice_noc.png")
    }

    @Test
    fun routePreview() {
        val ms = listOf(maneuver(ManeuverType.DEPART, "Jeďte", at = 0.0, len = 10.0), maneuver(ManeuverType.ARRIVE, "Cíl", at = 10.0, len = 0.0))
        val routes = listOf(
            Route("a", geometry, ms, 76_800.0, 3_552.0, "D1, D46", hasToll = true, hasHighway = true),
            Route("b", geometry, ms, 71_200.0, 4_380.0, "I/46, II/150", hasHighway = false),
        )
        val state = NavigationState.Preview(Place("Olomouc", GeoPoint(49.59, 17.25), "Horní náměstí, Olomouc"), routes, 0)
        compose.setContent {
            NavTheme(false) {
                Box(Modifier.fillMaxSize()) {
                    FakeMap(false)
                    RoutePreviewSheet(state, AppSettings(), {}, {}, {}, {}, {}, Modifier.align(Alignment.BottomCenter))
                }
            }
        }
        compose.onRoot().captureRoboImage("$out/3_nahled_trasy.png")
    }

    @Test
    fun idleWithSearch() {
        val home = Place("Domov", GeoPoint(49.2, 16.6))
        val saved = SavedPlaces(home = home, work = Place("Mendelu", GeoPoint(49.21, 16.61)), recents = listOf(Place("Lysá hora", GeoPoint(49.54, 18.45))))
        compose.setContent {
            NavTheme(false) {
                Box(Modifier.fillMaxSize()) {
                    FakeMap(false)
                    Column(Modifier.padding(12.dp).padding(top = 28.dp)) {
                        SearchBar({}, {})
                        Spacer(Modifier.height(10.dp))
                        QuickPlaces(saved, {})
                    }
                    MapControls(CameraState(), {}, {}, {}, {}, {}, {}, {}, Modifier.align(Alignment.BottomEnd).padding(12.dp))
                }
            }
        }
        compose.onRoot().captureRoboImage("$out/4_vychozi_obrazovka.png")
    }

    @Test
    fun maneuverIconSheet() {
        compose.setContent {
            NavTheme(true) {
                Column(Modifier.background(Color(0xFF0E7C5A)).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ManeuverType.entries.filter { it != ManeuverType.UNKNOWN }.chunked(4).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            row.forEach { type ->
                                Column(Modifier.size(width = 92.dp, height = 86.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                    ManeuverIcon(maneuver(type, type.name, at = 0.0, len = 0.0, exits = 3), 56.dp)
                                    Text(type.name.lowercase(), color = Color.White, style = MaterialTheme.typography.labelMedium, maxLines = 1)
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
