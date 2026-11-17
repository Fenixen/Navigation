package cz.fenixen.navigation.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cz.fenixen.navigation.R
import cz.fenixen.navigation.core.format.CzechFormat
import cz.fenixen.navigation.core.model.Place
import cz.fenixen.navigation.core.model.Route
import cz.fenixen.navigation.core.navigation.NavigationState
import cz.fenixen.navigation.data.AppSettings
import cz.fenixen.navigation.ui.theme.Hud

@Composable
private fun BottomCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    GlassPanel(modifier.widthIn(max = 640.dp).fillMaxWidth(), shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp)) {
        Column(Modifier.navigationBarsPadding().padding(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 16.dp)) {
            // „Madlo“ – vizuální nápověda, že jde o panel nad mapou.
            Box(Modifier.align(Alignment.CenterHorizontally).size(width = 36.dp, height = 4.dp).clip(CircleShape).background(Hud.Stroke))
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

/** Big papaya call-to-action. */
@Composable
fun PrimaryButton(text: String, icon: Int?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(onClick = onClick, shape = RoundedCornerShape(22.dp), color = Color.Transparent, modifier = modifier.height(60.dp)) {
        Box(Modifier.fillMaxSize().background(Hud.PapayaGradient), contentAlignment = Alignment.Center) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                icon?.let { Icon(painterResource(it), null, Modifier.size(22.dp), tint = Color(0xFF1A0D00)) }
                if (icon != null) Spacer(Modifier.width(10.dp))
                Text(text.uppercase(), color = Color(0xFF1A0D00), fontWeight = FontWeight.Black, fontSize = 17.sp, letterSpacing = 1.5.sp)
            }
        }
    }
}

@Composable
fun RoutePreviewSheet(
    state: NavigationState.Preview,
    settings: AppSettings,
    onSelect: (Int) -> Unit,
    onStart: () -> Unit,
    onCancel: () -> Unit,
    onAvoidTolls: (Boolean) -> Unit,
    onAvoidHighways: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    BottomCard(modifier) {
        Text(state.destination.name, style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        state.destination.address?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = Hud.TextSecondary, maxLines = 1) }
        Spacer(Modifier.height(14.dp))
        val times = state.routes.map(::effectiveDuration)
        val fastest = times.min()
        state.routes.forEachIndexed { index, route ->
            RouteOption(route, times[index], selected = index == state.selectedIndex, fastest = times[index] == fastest) { onSelect(index) }
            Spacer(Modifier.height(8.dp))
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AvoidChip(stringResource(R.string.avoid_tolls), settings.avoidTolls) { onAvoidTolls(!settings.avoidTolls) }
            AvoidChip(stringResource(R.string.avoid_highways), settings.avoidHighways) { onAvoidHighways(!settings.avoidHighways) }
        }
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            RoundButton(R.drawable.ic_close, stringResource(R.string.cancel), onCancel, size = 60.dp)
            Spacer(Modifier.width(12.dp))
            PrimaryButton(stringResource(R.string.start), R.drawable.ic_navigation, onStart, Modifier.weight(1f))
        }
    }
}

/** With live traffic, show traffic time (what you'll really drive), otherwise router time. */
private fun effectiveDuration(route: Route): Double = route.traffic?.travelTimeSeconds ?: route.durationSeconds

@Composable
private fun AvoidChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        shape = RoundedCornerShape(14.dp),
        colors = FilterChipDefaults.filterChipColors(
            containerColor = Hud.PanelHigh,
            labelColor = Hud.TextSecondary,
            selectedContainerColor = Hud.Papaya.copy(alpha = 0.18f),
            selectedLabelColor = Hud.PapayaLight,
        ),
        border = null,
    )
}

@Composable
private fun RouteOption(route: Route, seconds: Double, selected: Boolean, fastest: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        color = if (selected) Hud.PanelHigh else Hud.Panel,
        border = if (selected) BorderStroke(2.dp, Hud.Papaya) else BorderStroke(1.dp, Hud.Stroke),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(CzechFormat.duration(seconds), style = Hud.Number.copy(fontSize = 26.sp, color = if (fastest) Hud.Papaya else Hud.TextPrimary))
                    TrafficChip(route.traffic?.delaySeconds)
                    if (fastest) {
                        Spacer(Modifier.width(8.dp))
                        Badge(stringResource(R.string.fastest), Hud.Mint)
                    }
                }
                val via = route.summary.takeIf { it.isNotBlank() }?.let { " · přes $it" }.orEmpty()
                Text(
                    CzechFormat.distance(route.distanceMeters) + via,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Hud.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (route.hasToll) Badge(stringResource(R.string.toll), Hud.Amber)
                if (route.hasHighway) Badge(stringResource(R.string.highway), Hud.TextSecondary)
                if (route.hasFerry) Badge(stringResource(R.string.ferry), Hud.RouteBlue)
            }
        }
    }
}

@Composable
private fun Badge(text: String, color: Color) {
    Text(
        text.uppercase(),
        modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(color.copy(alpha = 0.15f)).padding(horizontal = 8.dp, vertical = 3.dp),
        color = color,
        fontSize = 10.sp,
        fontWeight = FontWeight.Black,
        letterSpacing = 0.8.sp,
    )
}

@Composable
fun PlanningSheet(destination: Place, onCancel: () -> Unit, modifier: Modifier = Modifier) {
    BottomCard(modifier) {
        Text(destination.name, style = MaterialTheme.typography.headlineSmall, maxLines = 1)
        Spacer(Modifier.height(4.dp))
        Text(stringResource(R.string.planning), color = Hud.TextSecondary)
        Spacer(Modifier.height(14.dp))
        LinearProgressIndicator(Modifier.fillMaxWidth().clip(CircleShape), color = Hud.Papaya, trackColor = Hud.PanelHigh)
        Spacer(Modifier.height(14.dp))
        RoundButton(R.drawable.ic_close, stringResource(R.string.cancel), onCancel)
    }
}

@Composable
fun FailedSheet(state: NavigationState.Failed, onRetry: () -> Unit, onCancel: () -> Unit, modifier: Modifier = Modifier) {
    BottomCard(modifier) {
        state.destination?.let { Text(it.name, style = MaterialTheme.typography.headlineSmall, maxLines = 1) }
        Spacer(Modifier.height(6.dp))
        Text(state.message, color = Hud.Red, style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            RoundButton(R.drawable.ic_close, stringResource(R.string.cancel), onCancel, size = 60.dp)
            Spacer(Modifier.width(12.dp))
            PrimaryButton(stringResource(R.string.retry), null, onRetry, Modifier.weight(1f))
        }
    }
}

@Composable
fun ArrivedSheet(destination: Place, onDone: () -> Unit, modifier: Modifier = Modifier) {
    BottomCard(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(56.dp).clip(RoundedCornerShape(18.dp)).background(Hud.PapayaGradient), contentAlignment = Alignment.Center) {
                Icon(painterResource(R.drawable.ic_flag), null, tint = Color(0xFF1A0D00), modifier = Modifier.size(28.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.arrived), style = MaterialTheme.typography.headlineSmall)
                Text(destination.name, color = Hud.TextSecondary, maxLines = 1)
            }
        }
        Spacer(Modifier.height(16.dp))
        PrimaryButton(stringResource(R.string.done), null, onDone, Modifier.fillMaxWidth())
    }
}
