package cz.fenixen.navigation.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Palette: green = "go" (banner, ETA), blue = route, red = warnings/destination. */
object NavColors {
    val Green = Color(0xFF0E7C5A)
    val GreenDark = Color(0xFF0A5C43)
    val GreenNight = Color(0xFF0B5E49)
    val RouteBlue = Color(0xFF2F6FED)
    val Danger = Color(0xFFE5484D)
    val SignRed = Color(0xFFD7262E)
}

private val LightColors = lightColorScheme(
    primary = NavColors.Green,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFCDEFE2),
    onPrimaryContainer = Color(0xFF00382A),
    secondary = NavColors.RouteBlue,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFDCE6FF),
    onSecondaryContainer = Color(0xFF0B2A6B),
    error = NavColors.Danger,
    background = Color(0xFFF3F5F7),
    surface = Color.White,
    surfaceVariant = Color(0xFFEDEFF2),
    onSurfaceVariant = Color(0xFF5B6472),
    outline = Color(0xFFC4CAD3),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF5FD3A8),
    onPrimary = Color(0xFF00382A),
    primaryContainer = NavColors.GreenNight,
    onPrimaryContainer = Color(0xFFCDEFE2),
    secondary = Color(0xFF8EB0FF),
    onSecondary = Color(0xFF0B2A6B),
    secondaryContainer = Color(0xFF1E3A78),
    onSecondaryContainer = Color(0xFFDCE6FF),
    error = Color(0xFFFF8A8E),
    background = Color(0xFF111418),
    surface = Color(0xFF1B1F25),
    surfaceVariant = Color(0xFF262B33),
    onSurfaceVariant = Color(0xFFAAB2BF),
    outline = Color(0xFF3B424D),
)

private val NavTypography = Typography(
    displaySmall = TextStyle(fontSize = 34.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.5).sp),
    headlineSmall = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.Bold),
    titleLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 16.sp),
    bodyMedium = TextStyle(fontSize = 14.sp),
    labelLarge = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
    labelMedium = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium),
)

@Composable
fun NavTheme(dark: Boolean, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        typography = NavTypography,
        content = content,
    )
}
