package cz.fenixen.navigation.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.Typeface
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Design tokens – "telemetry" look: dark glass panels over the map, papaya accent,
 * condensed tabular numerals (numbers don't jump when they change), small caps labels.
 * The HUD stays dark even when the map is in day mode → constant contrast in the car.
 */
object Hud {
    val Papaya = Color(0xFFFF8000)
    val PapayaLight = Color(0xFFFFA347)
    val PapayaDeep = Color(0xFFFF5F00)
    val Mint = Color(0xFF3DDC97)
    val Amber = Color(0xFFFFC233)
    val Red = Color(0xFFFF453A)
    val SignRed = Color(0xFFD7262E)
    val MotorwayGreen = Color(0xFF0B7A3E)
    val RouteBlue = Color(0xFF2F7BFF)

    val Glass = Color(0xEB0D1016)
    val GlassStrong = Color(0xF70D1016)
    val Panel = Color(0xFF161B23)
    val PanelHigh = Color(0xFF1E2430)
    val Background = Color(0xFF090B0F)
    val Stroke = Color(0x14FFFFFF)
    val TextPrimary = Color(0xFFF4F6F9)
    val TextSecondary = Color(0xFF9AA3AF)
    val TextTertiary = Color(0xFF5F6875)

    val PapayaGradient = Brush.verticalGradient(listOf(PapayaLight, PapayaDeep))
    val GlassBorder = BorderStroke(1.dp, Stroke)
    val CardShape = RoundedCornerShape(28.dp)

    /** Condensed family for big numbers ("telemetry" digits). */
    val Condensed = FontFamily(Typeface(android.graphics.Typeface.create(android.graphics.Typeface.create("sans-serif-condensed", android.graphics.Typeface.NORMAL), 800, false)))
    val CondensedMedium = FontFamily(Typeface(android.graphics.Typeface.create(android.graphics.Typeface.create("sans-serif-condensed", android.graphics.Typeface.NORMAL), 600, false)))

    val BigNumber = TextStyle(fontFamily = Condensed, fontSize = 46.sp, letterSpacing = (-0.5).sp, fontFeatureSettings = "tnum", color = TextPrimary)
    val Number = TextStyle(fontFamily = Condensed, fontSize = 30.sp, fontFeatureSettings = "tnum", color = TextPrimary)
    val SmallNumber = TextStyle(fontFamily = CondensedMedium, fontSize = 20.sp, fontFeatureSettings = "tnum", color = TextPrimary)
    val Label = TextStyle(fontSize = 10.5.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp, color = TextSecondary)
}

private val HudColors = darkColorScheme(
    primary = Hud.Papaya,
    onPrimary = Color(0xFF1A0D00),
    primaryContainer = Color(0xFF3A2208),
    onPrimaryContainer = Hud.PapayaLight,
    secondary = Hud.RouteBlue,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFF14264A),
    onSecondaryContainer = Color(0xFFCFE0FF),
    tertiary = Hud.Mint,
    error = Hud.Red,
    errorContainer = Color(0xFF3A1214),
    onErrorContainer = Color(0xFFFFB4AE),
    background = Hud.Background,
    onBackground = Hud.TextPrimary,
    surface = Hud.Panel,
    onSurface = Hud.TextPrimary,
    surfaceVariant = Hud.PanelHigh,
    onSurfaceVariant = Hud.TextSecondary,
    surfaceContainer = Hud.Panel,
    surfaceContainerHigh = Hud.PanelHigh,
    outline = Color(0xFF2C333F),
    outlineVariant = Color(0xFF222833),
)

private val HudTypography = Typography(
    displaySmall = TextStyle(fontSize = 34.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.5).sp),
    headlineSmall = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.2).sp),
    titleLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.1).sp),
    titleMedium = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 16.sp),
    bodyMedium = TextStyle(fontSize = 14.sp),
    labelLarge = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.2.sp),
    labelMedium = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.3.sp),
)

/** [dark] only affects the map style; the HUD is always the dark telemetry theme. */
@Composable
fun NavTheme(@Suppress("UNUSED_PARAMETER") dark: Boolean = true, content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = HudColors, typography = HudTypography, content = content)
}
