package cz.fenixen.navigation.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import cz.fenixen.navigation.R
import cz.fenixen.navigation.core.navigation.MapOrientation
import cz.fenixen.navigation.data.AppSettings
import cz.fenixen.navigation.data.DisplayKind
import cz.fenixen.navigation.data.MapTheme
import java.util.Locale

@Composable
fun SettingsScreen(settings: AppSettings, vm: MainViewModel, onToggleOrientation: () -> Unit, onBack: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.statusBarsPadding().navigationBarsPadding()) {
            Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_arrow_back), null) }
                Text(stringResource(R.string.settings), style = MaterialTheme.typography.headlineSmall)
            }
            Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
                Section("Navigace")
                SwitchRow(stringResource(R.string.voice_guidance), settings.voiceEnabled, onChange = vm::setVoiceEnabled)
                SwitchRow(stringResource(R.string.map_orientation), settings.orientation == MapOrientation.HEADING_UP) { onToggleOrientation() }

                Section(stringResource(R.string.avoid))
                SwitchRow(stringResource(R.string.avoid_tolls), settings.avoidTolls) { vm.setAvoidance(tolls = it) }
                SwitchRow(stringResource(R.string.avoid_highways), settings.avoidHighways) { vm.setAvoidance(highways = it) }
                SwitchRow(stringResource(R.string.avoid_ferries), settings.avoidFerries) { vm.setAvoidance(ferries = it) }

                Section("Zoom")
                ZoomLockRow(stringResource(R.string.zoom_lock_phone), settings.phoneZoomLock) { vm.clearZoomLock(DisplayKind.PHONE) }
                ZoomLockRow(stringResource(R.string.zoom_lock_car), settings.carZoomLock) { vm.clearZoomLock(DisplayKind.CAR) }

                Section(stringResource(R.string.map_theme))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MapTheme.entries.forEach { theme ->
                        val label = when (theme) {
                            MapTheme.AUTO -> R.string.theme_auto
                            MapTheme.DAY -> R.string.theme_day
                            MapTheme.NIGHT -> R.string.theme_night
                        }
                        FilterChip(selected = settings.mapTheme == theme, onClick = { vm.setMapTheme(theme) }, label = { Text(stringResource(label)) })
                    }
                }

                Section("Servery")
                UrlField(stringResource(R.string.routing_server), settings.routingUrl, vm::setRoutingUrl)
                Spacer(Modifier.height(8.dp))
                UrlField(stringResource(R.string.geocoder_server), settings.geocoderUrl, vm::setGeocoderUrl)

                Section("Vývoj")
                SwitchRow(
                    stringResource(R.string.simulate_driving),
                    settings.simulateDriving,
                    subtitle = stringResource(R.string.simulate_driving_hint),
                    onChange = vm::setSimulateDriving,
                )

                Spacer(Modifier.height(24.dp))
                Text(stringResource(R.string.attribution), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun Section(title: String) {
    Spacer(Modifier.height(20.dp))
    Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    Spacer(Modifier.height(4.dp))
    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
}

@Composable
private fun SwitchRow(title: String, checked: Boolean, subtitle: String? = null, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun ZoomLockRow(title: String, lock: Double?, onUnlock: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                lock?.let { "Zamčeno na " + String.format(Locale.ROOT, "%.1f", it) } ?: stringResource(R.string.zoom_lock_off),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (lock != null) TextButton(onClick = onUnlock) { Text(stringResource(R.string.unlock)) }
    }
}

@Composable
private fun UrlField(label: String, value: String, onSave: (String) -> Unit) {
    var text by remember(value) { mutableStateOf(value) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(value = text, onValueChange = { text = it }, label = { Text(label) }, singleLine = true, modifier = Modifier.weight(1f))
        if (text != value) TextButton(onClick = { onSave(text) }) { Text("Uložit") }
    }
}
