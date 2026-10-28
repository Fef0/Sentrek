package app.sentrek.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.sentrek.R
import app.sentrek.geo.BoundingBox
import app.sentrek.geo.Geo
import app.sentrek.geo.LatLon
import app.sentrek.geo.route.RoutePlace
import app.sentrek.home.PlaceBadge
import app.sentrek.home.kindLabel
import app.sentrek.record.around
import app.sentrek.record.placeKindLabel
import app.sentrek.ui.Format
import app.sentrek.ui.MapButton
import app.sentrek.ui.SentrekIcons
import app.sentrek.ui.TouchTarget
import app.sentrek.ui.safeTopPadding
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/**
 * One box for everything with a name: saved places (and their notes), tracks, and the huts,
 * peaks, springs… of the downloaded areas, offline. A result opens the map there.
 */
@Composable
fun SearchScreen(
    near: LatLon?,
    onBack: () -> Unit,
    onShowOnMap: (BoundingBox) -> Unit,
    viewModel: SearchViewModel = hiltViewModel(),
) {
    val query by viewModel.query.collectAsStateWithLifecycle()
    val results by viewModel.results.collectAsStateWithLifecycle()
    val pointsReady by viewModel.pointsReady.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        viewModel.open(near)
        focus.requestFocus()
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        Surface(shadowElevation = 4.dp) {
            Row(
                modifier = Modifier.fillMaxWidth().safeTopPadding().padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                MapButton(SentrekIcons.Back, stringResource(R.string.search_back), onBack)
                OutlinedTextField(
                    value = query,
                    onValueChange = { viewModel.query.value = it },
                    placeholder = { Text(stringResource(R.string.search_hint), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    leadingIcon = { Icon(SentrekIcons.Search, contentDescription = null) },
                    trailingIcon = if (query.isEmpty()) null else {
                        {
                            IconButton(onClick = { viewModel.query.value = ""; focus.requestFocus() }) {
                                Icon(SentrekIcons.Close, stringResource(R.string.search_clear))
                            }
                        }
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                    modifier = Modifier.weight(1f).focusRequester(focus),
                )
            }
        }

        val blank = query.isBlank()
        if (blank || results.isEmpty) {
            Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                Text(
                    stringResource(
                        when {
                            blank -> R.string.search_intro
                            !pointsReady -> R.string.search_reading
                            else -> R.string.search_nothing
                        },
                    ),
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                )
            }
            return@Column
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        ) {
            section(R.string.search_places, results.places, key = { "p" + it.id }) { place ->
                ResultRow(
                    badge = { PlaceBadge(RoutePlace.Kind.Saved(place.kind, place.displayColor), BADGE) },
                    name = place.name,
                    details = listOfNotNull(placeKindLabel(place.kind), away(near, place.position), place.note.takeIf { it.isNotEmpty() })
                        .joinToString(" · "),
                    onClick = { onShowOnMap(around(place.position)) },
                )
            }
            section(R.string.search_tracks, results.tracks, key = { "t" + it.id }) { track ->
                ResultRow(
                    badge = {
                        Surface(Modifier.size(BADGE), shape = CircleShape, color = Color(track.color), contentColor = Color.White) {
                            Icon(SentrekIcons.Tracks, contentDescription = null, modifier = Modifier.padding(6.dp))
                        }
                    },
                    name = track.name,
                    details = listOfNotNull(
                        track.startTime.takeIf { it > 0 }?.let { DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(it)) },
                        Format.distance(track.distance),
                    ).joinToString(" · "),
                    onClick = { scope.launch { viewModel.show(track)?.let(onShowOnMap) } },
                )
            }
            section(R.string.search_map, results.points, key = { "m${it.kind}${it.position.lat}${it.position.lon}" }) { poi ->
                val kind = RoutePlace.Kind.Osm(poi.kind)
                ResultRow(
                    badge = { PlaceBadge(kind, BADGE) },
                    name = poi.name.orEmpty(),
                    details = listOfNotNull(
                        kindLabel(kind),
                        poi.position.ele?.let(Format::elevation),
                        away(near, poi.position),
                    ).joinToString(" · "),
                    onClick = { onShowOnMap(around(poi.position)) },
                )
            }
            item { Box(Modifier.navigationBarsPadding()) }
        }
    }
}

private fun <T> LazyListScope.section(title: Int, items: List<T>, key: (T) -> String, row: @Composable (T) -> Unit) {
    if (items.isEmpty()) return
    item(key = "header$title") {
        Text(
            stringResource(title),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 4.dp, top = 12.dp, bottom = 4.dp),
        )
    }
    items(items, key = key) { row(it) }
}

@Composable
private fun ResultRow(badge: @Composable () -> Unit, name: String, details: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = TouchTarget)
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        badge()
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (details.isNotEmpty()) {
                Text(details, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** From the middle of the map the user came from. */
private fun away(near: LatLon?, p: LatLon): String? = near?.let { Format.distance(Geo.distance(it, p)) }

private val BADGE: Dp
    @Composable get() = TouchTarget * 0.6f
