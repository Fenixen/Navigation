package cz.fenixen.navigation.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cz.fenixen.navigation.R
import cz.fenixen.navigation.core.format.CzechFormat
import cz.fenixen.navigation.core.model.Maneuver
import cz.fenixen.navigation.core.navigation.NavigationState
import cz.fenixen.navigation.map.ManeuverIcons
import cz.fenixen.navigation.ui.theme.NavColors
import kotlin.math.roundToInt

@Composable
fun ManeuverIcon(maneuver: Maneuver, size: androidx.compose.ui.unit.Dp, tint: Color = Color.White) {
    val px = with(LocalDensity.current) { size.roundToPx() }
    val argb = android.graphics.Color.argb(
        (tint.alpha * 255).roundToInt(), (tint.red * 255).roundToInt(), (tint.green * 255).roundToInt(), (tint.blue * 255).roundToInt(),
    )
    val bitmap = remember(maneuver.type, maneuver.roundaboutExitCount, px, argb) { ManeuverIcons.bitmap(maneuver, px, argb) }
    Image(bitmap = bitmap.asImageBitmap(), contentDescription = maneuver.instruction, modifier = Modifier.size(size))
}

/** Top banner: big arrow, distance and where to go. Readable at a glance (≤ 2 s glance rule). */
@Composable
fun ManeuverBanner(state: NavigationState.Navigating, dark: Boolean, modifier: Modifier = Modifier) {
    val upcoming = state.upcoming
    val progress = state.progress
    val bannerColor = if (dark) NavColors.GreenNight else NavColors.Green
    Column(modifier.widthIn(max = 560.dp)) {
        Surface(
            color = bannerColor,
            contentColor = Color.White,
            shape = RoundedCornerShape(22.dp),
            shadowElevation = 8.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (state.isRerouting || upcoming == null || progress == null) {
                Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(color = Color.White, strokeWidth = 3.dp, modifier = Modifier.size(28.dp))
                    Spacer(Modifier.width(16.dp))
                    Text(
                        stringResource(if (state.isRerouting) R.string.rerouting else R.string.planning),
                        style = MaterialTheme.typography.titleLarge,
                    )
                }
            } else {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    ManeuverIcon(upcoming, 64.dp)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(CzechFormat.distance(progress.distanceToUpcoming), style = MaterialTheme.typography.displaySmall)
                        Text(
                            upcoming.primaryName ?: upcoming.instruction,
                            style = MaterialTheme.typography.titleLarge,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        signText(upcoming)?.let {
                            Text(it, style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.85f), maxLines = 1)
                        }
                    }
                }
            }
        }
        // „Potom…“ – když další manévr přijde hned po tomhle (do 250 m), ukážeme ho předem.
        val following = state.following
        val showThen = !state.isRerouting && upcoming != null && following != null &&
            !upcoming.type.isArrival && upcoming.lengthMeters < 250
        AnimatedVisibility(showThen) {
            if (following != null) {
                Surface(
                    color = if (dark) Color(0xFF08402F) else NavColors.GreenDark,
                    contentColor = Color.White,
                    shape = RoundedCornerShape(bottomStart = 16.dp, bottomEnd = 16.dp),
                    modifier = Modifier.padding(start = 20.dp),
                ) {
                    Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.then), style = MaterialTheme.typography.labelLarge)
                        Spacer(Modifier.width(8.dp))
                        ManeuverIcon(following, 24.dp)
                    }
                }
            }
        }
        if (state.rerouteFailed && !state.isRerouting) {
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.padding(top = 8.dp),
            ) {
                Text(
                    stringResource(R.string.reroute_failed),
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
}

private fun signText(m: Maneuver): String? {
    val sign = m.sign ?: return null
    val parts = buildList {
        sign.exitNumber?.let { add("Výjezd $it") }
        if (sign.toward.isNotEmpty()) add("směr " + sign.toward.take(2).joinToString(", "))
    }
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}

/** Bottom bar: end, ETA, remaining time/distance, overview + mute. */
@Composable
fun TripBar(
    state: NavigationState.Navigating,
    voiceEnabled: Boolean,
    onStop: () -> Unit,
    onOverview: () -> Unit,
    onToggleVoice: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val progress = state.progress
    Surface(
        shape = RoundedCornerShape(24.dp),
        shadowElevation = 8.dp,
        color = MaterialTheme.colorScheme.surface,
        modifier = modifier.widthIn(max = 560.dp).fillMaxWidth(),
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            FilledTonalIconButton(
                onClick = onStop,
                colors = IconButtonDefaults.filledTonalIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                ),
                modifier = Modifier.size(52.dp),
            ) { Icon(painterResource(R.drawable.ic_close), stringResource(R.string.end_navigation)) }
            Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                if (progress != null) {
                    val eta = System.currentTimeMillis() + (progress.durationRemaining * 1000).toLong()
                    Text(
                        CzechFormat.clock(eta),
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        "${CzechFormat.duration(progress.durationRemaining)} · ${CzechFormat.distance(progress.distanceRemaining)}",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(state.destination.name, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                }
            }
            FilledTonalIconButton(onClick = onOverview, modifier = Modifier.size(48.dp)) {
                Icon(painterResource(R.drawable.ic_overview), stringResource(R.string.overview))
            }
            Spacer(Modifier.width(8.dp))
            FilledTonalIconButton(onClick = onToggleVoice, modifier = Modifier.size(48.dp)) {
                Icon(
                    painterResource(if (voiceEnabled) R.drawable.ic_volume_up else R.drawable.ic_volume_off),
                    stringResource(if (voiceEnabled) R.string.mute else R.string.unmute),
                )
            }
        }
    }
}

/**
 * Current speed + Czech speed limit sign (B 20a: white disc, red ring).
 * Turns red when you're over the limit (+ tolerance in the session).
 */
@Composable
fun SpeedIndicator(speedMps: Double?, limitKph: Int?, speeding: Boolean, modifier: Modifier = Modifier) {
    val kmh = ((speedMps ?: 0.0) * 3.6).roundToInt()
    val bg by animateColorAsState(if (speeding) NavColors.Danger else MaterialTheme.colorScheme.surface, label = "speedBg")
    val fg = if (speeding) Color.White else MaterialTheme.colorScheme.onSurface
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Surface(shape = CircleShape, color = bg, shadowElevation = 6.dp, modifier = Modifier.size(64.dp)) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Text("$kmh", color = fg, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold)
                Text("km/h", color = fg.copy(alpha = 0.75f), fontSize = 10.sp)
            }
        }
        if (limitKph != null) {
            Box(
                Modifier
                    .size(56.dp)
                    .background(Color.White, CircleShape)
                    .border(6.dp, NavColors.SignRed, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text("$limitKph", color = Color.Black, fontSize = if (limitKph >= 100) 18.sp else 22.sp, fontWeight = FontWeight.Black)
            }
        }
    }
}
