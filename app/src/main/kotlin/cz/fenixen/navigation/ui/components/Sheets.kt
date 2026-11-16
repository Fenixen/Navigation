package cz.fenixen.navigation.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cz.fenixen.navigation.R
import cz.fenixen.navigation.core.format.CzechFormat
import cz.fenixen.navigation.core.model.Place
import cz.fenixen.navigation.core.model.Route
import cz.fenixen.navigation.core.navigation.NavigationState
import cz.fenixen.navigation.data.AppSettings

@Composable
private fun BottomCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        shadowElevation = 12.dp,
        color = MaterialTheme.colorScheme.surface,
        modifier = modifier.widthIn(max = 640.dp).fillMaxWidth(),
    ) {
        Column(Modifier.navigationBarsPadding().padding(20.dp)) { content() }
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
        Text(state.destination.name, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        state.destination.address?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
        Spacer(Modifier.height(14.dp))
        val fastest = state.routes.minOf { it.durationSeconds }
        state.routes.forEachIndexed { index, route ->
            RouteOption(route, selected = index == state.selectedIndex, fastest = route.durationSeconds == fastest) { onSelect(index) }
            Spacer(Modifier.height(8.dp))
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = settings.avoidTolls, onClick = { onAvoidTolls(!settings.avoidTolls) }, label = { Text(stringResource(R.string.avoid_tolls)) })
            FilterChip(selected = settings.avoidHighways, onClick = { onAvoidHighways(!settings.avoidHighways) }, label = { Text(stringResource(R.string.avoid_highways)) })
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onCancel, modifier = Modifier.height(56.dp)) { Text(stringResource(R.string.cancel)) }
            Button(onClick = onStart, modifier = Modifier.weight(1f).height(56.dp)) {
                Icon(painterResource(R.drawable.ic_navigation), null, Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.start), style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

@Composable
private fun RouteOption(route: Route, selected: Boolean, fastest: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.secondary) else null,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        CzechFormat.duration(route.durationSeconds),
                        style = MaterialTheme.typography.titleLarge,
                        color = if (fastest) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    )
                    if (fastest) {
                        Spacer(Modifier.width(8.dp))
                        Badge(stringResource(R.string.fastest), MaterialTheme.colorScheme.primaryContainer)
                    }
                }
                val via = route.summary.takeIf { it.isNotBlank() }?.let { " · přes $it" }.orEmpty()
                Text(
                    CzechFormat.distance(route.distanceMeters) + via,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (route.hasToll) Badge(stringResource(R.string.toll), MaterialTheme.colorScheme.errorContainer)
                if (route.hasHighway) Badge(stringResource(R.string.highway), MaterialTheme.colorScheme.surface)
                if (route.hasFerry) Badge(stringResource(R.string.ferry), MaterialTheme.colorScheme.surface)
            }
        }
    }
}

@Composable
private fun Badge(text: String, color: androidx.compose.ui.graphics.Color) {
    Surface(shape = RoundedCornerShape(8.dp), color = color) {
        Text(text, Modifier.padding(horizontal = 8.dp, vertical = 2.dp), style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
fun PlanningSheet(destination: Place, onCancel: () -> Unit, modifier: Modifier = Modifier) {
    BottomCard(modifier) {
        Text(destination.name, style = MaterialTheme.typography.titleLarge, maxLines = 1)
        Spacer(Modifier.height(4.dp))
        Text(stringResource(R.string.planning), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        LinearProgressIndicator(Modifier.fillMaxWidth())
        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
    }
}

@Composable
fun FailedSheet(state: NavigationState.Failed, onRetry: () -> Unit, onCancel: () -> Unit, modifier: Modifier = Modifier) {
    BottomCard(modifier) {
        state.destination?.let { Text(it.name, style = MaterialTheme.typography.titleLarge, maxLines = 1) }
        Spacer(Modifier.height(4.dp))
        Text(state.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
            Button(onClick = onRetry) { Text(stringResource(R.string.retry)) }
        }
    }
}

@Composable
fun ArrivedSheet(destination: Place, onDone: () -> Unit, modifier: Modifier = Modifier) {
    BottomCard(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(R.drawable.ic_place), null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(36.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.arrived), style = MaterialTheme.typography.titleLarge)
                Text(destination.name, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
        }
        Spacer(Modifier.height(16.dp))
        Button(onClick = onDone, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text(stringResource(R.string.done)) }
    }
}
