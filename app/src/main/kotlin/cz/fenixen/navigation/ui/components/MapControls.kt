package cz.fenixen.navigation.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import cz.fenixen.navigation.core.navigation.CameraState
import cz.fenixen.navigation.core.navigation.FollowMode
import cz.fenixen.navigation.core.navigation.MapOrientation
import cz.fenixen.navigation.core.navigation.ZoomPolicy
import java.util.Locale

@Composable
fun MapControls(
    camera: CameraState,
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
    onAutoZoom: () -> Unit,
    onLockCurrent: () -> Unit,
    onLockMax: () -> Unit,
    onRecenter: () -> Unit,
    onToggleOrientation: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp), horizontalAlignment = Alignment.End) {
        SmallFloatingActionButton(onClick = onToggleOrientation, containerColor = MaterialTheme.colorScheme.surface) {
            // Kompas: červený = sever nahoře, neutrální = mapa po směru jízdy.
            val north = camera.orientation == MapOrientation.NORTH_UP
            Icon(
                painterResource(R.drawable.ic_compass),
                contentDescription = stringResource(R.string.map_orientation),
                tint = if (north) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        ZoomLockChip(camera, onAutoZoom, onLockCurrent, onLockMax)
        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface, shadowElevation = 4.dp) {
            Column {
                SmallFloatingActionButton(onClick = onZoomIn, containerColor = MaterialTheme.colorScheme.surface, elevation = flatFab()) {
                    Icon(painterResource(R.drawable.ic_add), stringResource(R.string.zoom_in))
                }
                HorizontalDivider(Modifier.width(40.dp).align(Alignment.CenterHorizontally))
                SmallFloatingActionButton(onClick = onZoomOut, containerColor = MaterialTheme.colorScheme.surface, elevation = flatFab()) {
                    Icon(painterResource(R.drawable.ic_remove), stringResource(R.string.zoom_out))
                }
            }
        }
        AnimatedVisibility(camera.followMode != FollowMode.FOLLOW) {
            SmallFloatingActionButton(onClick = onRecenter, containerColor = MaterialTheme.colorScheme.secondary) {
                Icon(painterResource(R.drawable.ic_my_location), stringResource(R.string.recenter), Modifier.size(22.dp))
            }
        }
    }
}

@Composable
private fun flatFab() = androidx.compose.material3.FloatingActionButtonDefaults.elevation(0.dp, 0.dp, 0.dp, 0.dp)

/**
 * The lock chip shows the state honestly: "AUTO" or a padlock with the locked zoom level.
 * Menu: back to auto, lock the current zoom, or jump to max zoom and lock it forever.
 */
@Composable
private fun ZoomLockChip(camera: CameraState, onAutoZoom: () -> Unit, onLockCurrent: () -> Unit, onLockMax: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val locked = camera.zoomPolicy as? ZoomPolicy.Locked
    Box {
        Surface(
            onClick = { menu = true },
            shape = RoundedCornerShape(14.dp),
            color = if (locked != null) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.surface,
            contentColor = if (locked != null) MaterialTheme.colorScheme.onSecondary else MaterialTheme.colorScheme.onSurface,
            shadowElevation = 4.dp,
        ) {
            Row(Modifier.size(width = 76.dp, height = 40.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                Icon(
                    painterResource(if (locked != null) R.drawable.ic_lock else R.drawable.ic_auto_zoom),
                    contentDescription = stringResource(if (locked != null) R.string.zoom_locked else R.string.zoom_auto),
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    locked?.let { String.format(Locale.ROOT, "%.1f", it.zoom) } ?: stringResource(R.string.zoom_auto),
                    style = MaterialTheme.typography.labelLarge,
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
                leadingIcon = { Icon(painterResource(R.drawable.ic_zoom_max), null) },
                onClick = { menu = false; onLockMax() },
            )
        }
    }
}
