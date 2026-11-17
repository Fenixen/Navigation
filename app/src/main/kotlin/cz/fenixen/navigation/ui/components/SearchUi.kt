package cz.fenixen.navigation.ui.components

import androidx.compose.foundation.background
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cz.fenixen.navigation.R
import cz.fenixen.navigation.core.format.CzechFormat
import cz.fenixen.navigation.core.geo.Geo
import cz.fenixen.navigation.core.geo.GeoPoint
import cz.fenixen.navigation.core.model.Place
import cz.fenixen.navigation.data.SavedPlaces
import cz.fenixen.navigation.ui.SearchUiState
import cz.fenixen.navigation.ui.theme.Hud

/** Floating glass search pill on the idle map. */
@Composable
fun SearchBar(onClick: () -> Unit, onSettings: () -> Unit, modifier: Modifier = Modifier) {
    GlassPanel(modifier.widthIn(max = 560.dp).fillMaxWidth().height(64.dp), shape = RoundedCornerShape(32.dp)) {
        Row(Modifier.clickable(onClick = onClick).padding(start = 10.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clip(CircleShape).background(Hud.PapayaGradient), contentAlignment = Alignment.Center) {
                Icon(painterResource(R.drawable.ic_search), null, tint = Color(0xFF1A0D00), modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.width(14.dp))
            Text(
                stringResource(R.string.search_hint),
                style = MaterialTheme.typography.titleMedium,
                color = Hud.TextSecondary,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onSettings) { Icon(painterResource(R.drawable.ic_settings), stringResource(R.string.settings), tint = Hud.TextSecondary) }
        }
    }
}

/** Home / Work / recent destinations as one-tap glass chips. */
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
            GlassPanel(shape = RoundedCornerShape(20.dp)) {
                Row(Modifier.clickable { onPlace(place) }.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(painterResource(icon), null, Modifier.size(18.dp), tint = Hud.Papaya)
                    Spacer(Modifier.width(8.dp))
                    Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 160.dp))
                }
            }
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
    Surface(color = Hud.Background, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.statusBarsPadding().imePadding()) {
            Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                RoundButton(R.drawable.ic_arrow_back, "Zpět", onBack, size = 48.dp)
                Spacer(Modifier.width(10.dp))
                TextField(
                    value = query,
                    onValueChange = onQueryChange,
                    placeholder = { Text(stringResource(R.string.search_hint)) },
                    singleLine = true,
                    shape = RoundedCornerShape(24.dp),
                    leadingIcon = { Icon(painterResource(R.drawable.ic_search), null, tint = Hud.Papaya) },
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Hud.Panel,
                        unfocusedContainerColor = Hud.Panel,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        cursorColor = Hud.Papaya,
                    ),
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { onQueryChange("") }) { Icon(painterResource(R.drawable.ic_close), null) }
                        }
                    },
                    modifier = Modifier.weight(1f).focusRequester(focus),
                )
            }
            if (state is SearchUiState.Loading) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp), color = Hud.Papaya, trackColor = Color.Transparent)
            LazyColumn(Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
                when {
                    query.isBlank() -> {
                        if (saved.home != null || saved.work != null) item { SectionLabel("Oblíbené") }
                        saved.home?.let { item { PlaceRow(it, R.drawable.ic_home, "Domů", myLocation, onPlace, onSetHome, onSetWork) } }
                        saved.work?.let { item { PlaceRow(it, R.drawable.ic_work, "Práce", myLocation, onPlace, onSetHome, onSetWork) } }
                        if (saved.recents.isNotEmpty()) item { SectionLabel("Nedávné") }
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
private fun SectionLabel(text: String) {
    Text(text.uppercase(), style = Hud.Label, modifier = Modifier.padding(start = 16.dp, top = 18.dp, bottom = 6.dp))
}

@Composable
private fun Hint(text: String) {
    Text(text, Modifier.padding(24.dp), color = Hud.TextSecondary)
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
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).clickable { onPlace(place) }.padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(Hud.PanelHigh), contentAlignment = Alignment.Center) {
            Icon(painterResource(icon), null, tint = Hud.Papaya, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(label ?: place.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val subtitle = if (label != null) place.name else place.address
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = Hud.TextSecondary, maxLines = 1) }
        }
        myLocation?.let {
            Text(CzechFormat.distance(Geo.distance(it, place.point)), style = Hud.SmallNumber.copy(color = Hud.TextSecondary), fontWeight = FontWeight.Medium)
        }
        Box {
            IconButton(onClick = { menu = true }) { Icon(painterResource(R.drawable.ic_star), null, tint = Hud.TextTertiary) }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text(stringResource(R.string.set_home)) }, onClick = { menu = false; onSetHome(place) })
                DropdownMenuItem(text = { Text(stringResource(R.string.set_work)) }, onClick = { menu = false; onSetWork(place) })
            }
        }
    }
}
