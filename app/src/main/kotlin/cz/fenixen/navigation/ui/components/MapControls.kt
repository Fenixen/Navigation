package cz.fenixen.navigation.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cz.fenixen.navigation.R
import cz.fenixen.navigation.core.navigation.CameraState
import cz.fenixen.navigation.core.navigation.MapOrientation
import cz.fenixen.navigation.core.navigation.ZoomPolicy
import cz.fenixen.navigation.ui.theme.Hud
import java.util.Locale

/**
 * One glass column: compass · + · zoom lock · −.
 * The lock tile is honest about state: papaya with the locked level, or "AUTO".
 */
@Composable
fun MapControls(
    camera: CameraState,
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
    onAutoZoom: () -> Unit,
    onLockCurrent: () -> Unit,
    onLockMax: () -> Unit,
    onToggleOrientation: () -> Unit,
    modifier: Modifier = Modifier,
) {
    GlassPanel(modifier.width(58.dp), shape = RoundedCornerShape(29.dp)) {
        Column(Modifier.padding(vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            val north = camera.orientation == MapOrientation.NORTH_UP
            ControlButton(onClick = onToggleOrientation, description = stringResource(R.string.map_orientation)) {
                // Sever nahoře = střelka rovně v oranžovém kroužku; po směru jízdy = natočená střelka.
                Box(
                    Modifier.size(36.dp).clip(CircleShape).background(if (north) Hud.Papaya.copy(alpha = 0.22f) else Color.Transparent),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painterResource(R.drawable.ic_compass),
                        null,
                        tint = Color.Unspecified,
                        modifier = Modifier.size(26.dp).rotate(if (north) 0f else -35f),
                    )
                }
            }
            Divider()
            ControlButton(onClick = onZoomIn, description = stringResource(R.string.zoom_in)) {
                Icon(painterResource(R.drawable.ic_add), null, Modifier.size(22.dp))
            }
            ZoomLockTile(camera, onAutoZoom, onLockCurrent, onLockMax)
            ControlButton(onClick = onZoomOut, description = stringResource(R.string.zoom_out)) {
                Icon(painterResource(R.drawable.ic_remove), null, Modifier.size(22.dp))
            }
        }
    }
}

@Composable
private fun ControlButton(onClick: () -> Unit, description: String, content: @Composable () -> Unit) {
    Surface(onClick = onClick, color = Color.Transparent, contentColor = Hud.TextPrimary, shape = RoundedCornerShape(20.dp), modifier = Modifier.size(50.dp)) {
        Box(contentAlignment = Alignment.Center) { content() }
    }
}

@Composable
private fun Divider() {
    Box(Modifier.padding(vertical = 2.dp).width(26.dp).height(1.dp).background(Hud.Stroke))
}

@Composable
private fun ZoomLockTile(camera: CameraState, onAutoZoom: () -> Unit, onLockCurrent: () -> Unit, onLockMax: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val locked = camera.zoomPolicy as? ZoomPolicy.Locked
    Box {
        Surface(
            onClick = { menu = true },
            shape = RoundedCornerShape(16.dp),
            color = if (locked != null) Hud.Papaya else Color.White.copy(alpha = 0.07f),
            contentColor = if (locked != null) Color(0xFF1A0D00) else Hud.TextPrimary,
            modifier = Modifier.padding(vertical = 4.dp).size(width = 46.dp, height = 50.dp),
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center) {
                Icon(
                    painterResource(if (locked != null) R.drawable.ic_lock else R.drawable.ic_auto_zoom),
                    contentDescription = stringResource(if (locked != null) R.string.zoom_locked else R.string.zoom_auto),
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    locked?.let { String.format(Locale.ROOT, "%.1f", it.zoom) } ?: stringResource(R.string.zoom_auto),
                    style = Hud.SmallNumber.copy(fontSize = 13.sp, color = Color.Unspecified),
                    fontWeight = FontWeight.Bold,
                )
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
                text = { Text("Automatický zoom podle rychlosti") },
                leadingIcon = { Icon(painterResource(R.drawable.ic_auto_zoom), null) },
                onClick = { menu = false; onAutoZoom() },
            )
            DropdownMenuItem(
                text = { Text("Zamknout aktuální zoom") },
                leadingIcon = { Icon(painterResource(R.drawable.ic_lock), null) },
                onClick = { menu = false; onLockCurrent() },
            )
            DropdownMenuItem(
                text = { Text("Max zoom – napořád") },
                leadingIcon = { Icon(painterResource(R.drawable.ic_zoom_max), null, tint = Hud.Papaya) },
                onClick = { menu = false; onLockMax() },
            )
        }
    }
}
