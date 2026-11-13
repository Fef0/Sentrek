package app.sentrek.search

import androidx.compose.material3.OutlinedButton
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import app.sentrek.geo.overlay.NamedLine
import app.sentrek.map.MapOverlays
import app.sentrek.map.RouteMarks
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
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
import app.sentrek.record.MapName
import app.sentrek.home.kindName
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
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
    /** Opens the peak finder. */
    onPeaks: () -> Unit,
    /** A position typed or pasted: the map shows it, marked. */
    onShowPosition: (LatLon) -> Unit,
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
        // Still reading the areas, or the results are for what was typed before.
        val busy = !blank && (!pointsReady || results.query != query)
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        // Finding a place by name, or naming a place in sight: one screen for both.
        if (blank) {
            OutlinedButton(
                onClick = onPeaks,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp).heightIn(min = TouchTarget),
            ) {
                Icon(SentrekIcons.Peak, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                Text(stringResource(R.string.peaks_open), style = MaterialTheme.typography.titleMedium)
            }
        }
        if (blank || results.isEmpty) {
            Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    if (busy) CircularProgressIndicator()
                    Text(
                        stringResource(
                            when {
                                blank -> R.string.search_intro
                                !pointsReady -> R.string.search_reading
                                busy -> R.string.search_searching
                                else -> R.string.search_nothing
                            },
                        ),
                        style = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.Center,
                    )
                }
            }
            return@Column
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        ) {
            section(R.string.search_position, listOfNotNull(results.position), key = { "c" }) { position ->
                val coordinates = Format.coordinates(position.lat, position.lon)
                ResultRow(
                    badge = { RoundBadge(SentrekIcons.Place) },
                    name = coordinates,
                    details = listOfNotNull(stringResource(R.string.search_position_go), away(near, position)).joinToString(" · "),
                    onClick = { onShowPosition(position) },
                )
            }
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
            section(R.string.search_paths, results.paths, key = { "l${it.kind}${it.routeId ?: it.name}${it.box.south}${it.box.west}" }) { line ->
                ResultRow(
                    badge = { PathBadge(line) },
                    name = line.name,
                    details = listOfNotNull(
                        when (line.kind) {
                            NamedLine.Kind.Ferrata -> stringResource(R.string.kind_via_ferrata) +
                                (line.grade?.takeIf { it > 0 }?.let { " · " + MapOverlays.HUSLER_GRADES[it] } ?: "")
                            NamedLine.Kind.Route -> stringResource(R.string.search_path_route) + (line.ref?.takeIf { it != line.name }?.let { " · $it" } ?: "")
                            NamedLine.Kind.Path -> stringResource(R.string.search_path_path)
                        },
                        away(near, line.middle),
                    ).joinToString(" · "),
                    onClick = { onShowOnMap(line.box) },
                )
            }
            section(R.string.search_map, results.points, key = { "m${it.javaClass.simpleName}${it.name}${it.position.lat}${it.position.lon}" }) { hit ->
                val context = LocalContext.current
                when (hit) {
                    is MapHit.Hiking -> {
                        val kind = RoutePlace.Kind.Osm(hit.poi.kind)
                        ResultRow(
                            badge = { PlaceBadge(kind, BADGE) },
                            name = hit.name,
                            details = listOfNotNull(kindLabel(kind), hit.position.ele?.let(Format::elevation), away(near, hit.position))
                                .joinToString(" · "),
                            onClick = { onShowOnMap(around(hit.position)) },
                        )
                    }
                    is MapHit.Named -> ResultRow(
                        badge = {
                            RoundBadge(
                                when (hit.place.group) {
                                    MapName.Group.Mountain -> SentrekIcons.Peak
                                    MapName.Group.Water -> SentrekIcons.Water
                                    else -> SentrekIcons.Place
                                },
                            )
                        },
                        name = hit.name,
                        details = listOfNotNull(
                            hit.place.kind.takeIf { it.isNotBlank() }?.let { kindName(context, it) },
                            hit.position.ele?.let(Format::elevation),
                            away(near, hit.position),
                        ).joinToString(" · "),
                        onClick = { onShowOnMap(around(hit.position)) },
                    )
                }
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

/** A town, a mountain or a typed position: no colour of its own, the theme's. */
@Composable
private fun RoundBadge(icon: ImageVector) {
    Surface(
        Modifier.size(BADGE),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.padding(6.dp))
    }
}

/** From the middle of the map the user came from. */
/** A via ferrata: in the colours opposite the screen's (as on the day and night maps), with its grade; a route: its waymark; a path: a walker. */
@Composable
private fun PathBadge(line: NamedLine) {
    val density = LocalDensity.current.density
    val mark = remember(line.mark, density) {
        line.mark?.let { RouteMarks.image(RouteMarks.PREFIX + it, density)?.asImageBitmap() }
    }
    when {
        line.kind == NamedLine.Kind.Ferrata -> Surface(
            Modifier.size(BADGE),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.inverseSurface,
            contentColor = MaterialTheme.colorScheme.inverseOnSurface,
        ) {
            Box(contentAlignment = Alignment.Center) {
                val grade = line.grade?.takeIf { it > 0 }?.let { MapOverlays.HUSLER_GRADES[it] }
                if (grade != null) {
                    Text(grade, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                } else {
                    Icon(SentrekIcons.Hiking, contentDescription = null, modifier = Modifier.padding(6.dp))
                }
            }
        }
        mark != null -> Box(Modifier.size(BADGE), contentAlignment = Alignment.Center) {
            Image(mark, contentDescription = null, modifier = Modifier.size(BADGE * 0.9f))
        }
        else -> RoundBadge(SentrekIcons.Hiking)
    }
}

private fun away(near: LatLon?, p: LatLon): String? = near?.let { Format.distance(Geo.distance(it, p)) }

private val BADGE: Dp
    @Composable get() = TouchTarget * 0.6f
