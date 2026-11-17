package cz.fenixen.navigation.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import cz.fenixen.navigation.R
import cz.fenixen.navigation.core.navigation.MapOrientation
import cz.fenixen.navigation.data.AppSettings
import cz.fenixen.navigation.data.DisplayKind
import cz.fenixen.navigation.data.MapTheme
import cz.fenixen.navigation.ui.components.RoundButton
import cz.fenixen.navigation.ui.theme.Hud
import java.util.Locale

@Composable
fun SettingsScreen(settings: AppSettings, vm: MainViewModel, onToggleOrientation: () -> Unit, onBack: () -> Unit) {
    Surface(color = Hud.Background, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.statusBarsPadding().navigationBarsPadding()) {
            Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                RoundButton(R.drawable.ic_arrow_back, "Zpět", onBack, size = 48.dp)
                Spacer(Modifier.width(14.dp))
                Text(stringResource(R.string.settings), style = MaterialTheme.typography.headlineSmall)
            }
            Column(
                Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 14.dp).widthIn(max = 640.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Card("Navigace") {
                    SwitchRow(stringResource(R.string.voice_guidance), settings.voiceEnabled, onChange = vm::setVoiceEnabled)
                    SwitchRow(stringResource(R.string.map_orientation), settings.orientation == MapOrientation.HEADING_UP) { onToggleOrientation() }
                }
                Card(stringResource(R.string.traffic)) {
                    SwitchRow(
                        stringResource(R.string.traffic_live),
                        settings.trafficEnabled,
                        subtitle = stringResource(R.string.traffic_live_hint),
                        onChange = vm::setTrafficEnabled,
                    )
                    SwitchRow(stringResource(R.string.traffic_on_map), settings.trafficOnMap, onChange = vm::setTrafficOnMap)
                    TextSetting(stringResource(R.string.tomtom_key), settings.tomtomKey, secret = true, onSave = vm::setTomTomKey)
                }
                Card(stringResource(R.string.avoid)) {
                    SwitchRow(stringResource(R.string.avoid_tolls), settings.avoidTolls) { vm.setAvoidance(tolls = it) }
                    SwitchRow(stringResource(R.string.avoid_highways), settings.avoidHighways) { vm.setAvoidance(highways = it) }
                    SwitchRow(stringResource(R.string.avoid_ferries), settings.avoidFerries) { vm.setAvoidance(ferries = it) }
                }
                Card("Zoom") {
                    ZoomLockRow(stringResource(R.string.zoom_lock_phone), settings.phoneZoomLock) { vm.clearZoomLock(DisplayKind.PHONE) }
                    ZoomLockRow(stringResource(R.string.zoom_lock_car), settings.carZoomLock) { vm.clearZoomLock(DisplayKind.CAR) }
                }
                Card(stringResource(R.string.map_theme)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MapTheme.entries.forEach { theme ->
                            val label = when (theme) {
                                MapTheme.AUTO -> R.string.theme_auto
                                MapTheme.DAY -> R.string.theme_day
                                MapTheme.NIGHT -> R.string.theme_night
                            }
                            FilterChip(
                                selected = settings.mapTheme == theme,
                                onClick = { vm.setMapTheme(theme) },
                                label = { Text(stringResource(label)) },
                                shape = RoundedCornerShape(14.dp),
                                border = null,
                                colors = FilterChipDefaults.filterChipColors(
                                    containerColor = Hud.PanelHigh,
                                    selectedContainerColor = Hud.Papaya.copy(alpha = 0.2f),
                                    selectedLabelColor = Hud.PapayaLight,
                                ),
                            )
                        }
                    }
                }
                Card("Servery") {
                    TextSetting(stringResource(R.string.routing_server), settings.routingUrl, onSave = vm::setRoutingUrl)
                    TextSetting(stringResource(R.string.geocoder_server), settings.geocoderUrl, onSave = vm::setGeocoderUrl)
                }
                Card("Vývoj") {
                    SwitchRow(
                        stringResource(R.string.simulate_driving),
                        settings.simulateDriving,
                        subtitle = stringResource(R.string.simulate_driving_hint),
                        onChange = vm::setSimulateDriving,
                    )
                }
                Text(stringResource(R.string.attribution), style = MaterialTheme.typography.labelMedium, color = Hud.TextTertiary, modifier = Modifier.padding(8.dp))
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun Card(title: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = RoundedCornerShape(24.dp), color = Hud.Panel, border = Hud.GlassBorder, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 14.dp)) {
            Text(title.uppercase(), style = Hud.Label.copy(color = Hud.Papaya))
            Spacer(Modifier.height(6.dp))
            content()
        }
    }
}

@Composable
private fun SwitchRow(title: String, checked: Boolean, subtitle: String? = null, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = Hud.TextSecondary) }
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedTrackColor = Hud.Papaya, checkedThumbColor = Color(0xFF1A0D00)),
        )
    }
}

@Composable
private fun ZoomLockRow(title: String, lock: Double?, onUnlock: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                lock?.let { "Zamčeno na " + String.format(Locale.ROOT, "%.1f", it) } ?: stringResource(R.string.zoom_lock_off),
                style = MaterialTheme.typography.bodyMedium,
                color = if (lock != null) Hud.Papaya else Hud.TextSecondary,
            )
        }
        if (lock != null) TextButton(onClick = onUnlock) { Text(stringResource(R.string.unlock), color = Hud.PapayaLight) }
    }
}

@Composable
private fun TextSetting(label: String, value: String, secret: Boolean = false, onSave: (String) -> Unit) {
    var text by remember(value) { mutableStateOf(value) }
    Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            label = { Text(label) },
            singleLine = true,
            visualTransformation = if (secret && text == value && value.isNotEmpty()) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(keyboardType = if (secret) KeyboardType.Password else KeyboardType.Uri),
            shape = RoundedCornerShape(16.dp),
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Hud.Papaya, cursorColor = Hud.Papaya, focusedLabelColor = Hud.Papaya),
            modifier = Modifier.weight(1f),
        )
        if (text != value) TextButton(onClick = { onSave(text) }) { Text("Uložit", color = Hud.PapayaLight) }
    }
}
