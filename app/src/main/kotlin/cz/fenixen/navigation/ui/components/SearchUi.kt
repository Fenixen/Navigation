package cz.fenixen.navigation.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cz.fenixen.navigation.R
import cz.fenixen.navigation.core.format.CzechFormat
import cz.fenixen.navigation.core.geo.Geo
import cz.fenixen.navigation.core.geo.GeoPoint
import cz.fenixen.navigation.core.model.Place
import cz.fenixen.navigation.data.SavedPlaces
import cz.fenixen.navigation.ui.SearchUiState

/** Pill-shaped entry point on the idle map. */
@Composable
fun SearchBar(onClick: () -> Unit, onSettings: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(28.dp),
        shadowElevation = 6.dp,
        color = MaterialTheme.colorScheme.surface,
        modifier = modifier.widthIn(max = 560.dp).fillMaxWidth().height(56.dp),
    ) {
        Row(Modifier.padding(start = 18.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(R.drawable.ic_search), null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Text(
                stringResource(R.string.search_hint),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onSettings) { Icon(painterResource(R.drawable.ic_settings), stringResource(R.string.settings)) }
        }
    }
}

/** Home / Work / recent destinations as one-tap chips. */
@Composable
fun QuickPlaces(places: SavedPlaces, onPlace: (Place) -> Unit, modifier: Modifier = Modifier) {
    val chips = buildList {
        places.home?.let { add(Triple(R.drawable.ic_home, "Domů", it)) }
        places.work?.let { add(Triple(R.drawable.ic_work, "Práce", it)) }
        places.recents.take(5).forEach { add(Triple(R.drawable.ic_history, it.name, it)) }
    }
    if (chips.isEmpty()) return
    Row(modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        chips.forEach { (icon, label, place) ->
            AssistChip(
                onClick = { onPlace(place) },
                label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 160.dp)) },
                leadingIcon = { Icon(painterResource(icon), null, Modifier.size(18.dp)) },
                colors = AssistChipDefaults.assistChipColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = AssistChipDefaults.assistChipElevation(elevation = 3.dp),
                border = null,
            )
        }
    }
}

@Composable
fun SearchOverlay(
    query: String,
    state: SearchUiState,
    saved: SavedPlaces,
    myLocation: GeoPoint?,
    onQueryChange: (String) -> Unit,
    onBack: () -> Unit,
    onPlace: (Place) -> Unit,
    onSetHome: (Place) -> Unit,
    onSetWork: (Place) -> Unit,
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.statusBarsPadding().imePadding()) {
            Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_arrow_back), null) }
                TextField(
                    value = query,
                    onValueChange = onQueryChange,
                    placeholder = { Text(stringResource(R.string.search_hint)) },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    colors = TextFieldDefaults.colors(
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                    ),
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { onQueryChange("") }) { Icon(painterResource(R.drawable.ic_close), null) }
                        }
                    },
                    modifier = Modifier.weight(1f).focusRequester(focus),
                )
            }
            if (state is SearchUiState.Loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            LazyColumn(Modifier.fillMaxSize()) {
                when {
                    query.isBlank() -> {
                        saved.home?.let { item { PlaceRow(it, R.drawable.ic_home, "Domů", myLocation, onPlace, onSetHome, onSetWork) } }
                        saved.work?.let { item { PlaceRow(it, R.drawable.ic_work, "Práce", myLocation, onPlace, onSetHome, onSetWork) } }
                        items(saved.recents) { PlaceRow(it, R.drawable.ic_history, null, myLocation, onPlace, onSetHome, onSetWork) }
                    }
                    state is SearchUiState.Results && state.places.isEmpty() -> item { Hint(stringResource(R.string.no_results)) }
                    state is SearchUiState.Results -> items(state.places) {
                        PlaceRow(it, R.drawable.ic_place, null, myLocation, onPlace, onSetHome, onSetWork)
                    }
                    state is SearchUiState.Error -> item { Hint(state.message) }
                    else -> Unit
                }
            }
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun PlaceRow(
    place: Place,
    icon: Int,
    label: String?,
    myLocation: GeoPoint?,
    onPlace: (Place) -> Unit,
    onSetHome: (Place) -> Unit,
    onSetWork: (Place) -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().clickable { onPlace(place) }.padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(40.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Icon(painterResource(icon), null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(20.dp))
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(label ?: place.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val subtitle = if (label != null) place.name else place.address
            subtitle?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
        }
        myLocation?.let {
            Text(
                CzechFormat.distance(Geo.distance(it, place.point)),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Box {
            IconButton(onClick = { menu = true }) { Icon(painterResource(R.drawable.ic_star), null, tint = MaterialTheme.colorScheme.outline) }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text(stringResource(R.string.set_home)) }, onClick = { menu = false; onSetHome(place) })
                DropdownMenuItem(text = { Text(stringResource(R.string.set_work)) }, onClick = { menu = false; onSetWork(place) })
            }
        }
    }
    HorizontalDivider(Modifier.padding(start = 70.dp), color = MaterialTheme.colorScheme.surfaceVariant)
}
