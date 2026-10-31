package app.sentrek.home

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.sentrek.R
import app.sentrek.geo.overlay.SacScale
import app.sentrek.geo.overlay.TrailVisibility
import app.sentrek.map.MapFeatureInfo
import app.sentrek.map.MapRoute
import app.sentrek.map.RouteMarks
import app.sentrek.record.WeatherResult
import app.sentrek.record.WeatherSection
import app.sentrek.ui.Format
import app.sentrek.ui.TouchTarget
import java.util.Locale

/** Everything we know about a long-pressed map feature. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeatureSheet(
    info: MapFeatureInfo,
    onSaveAsPlace: () -> Unit,
    onGoTo: () -> Unit,
    onDismiss: () -> Unit,
    loadWeather: suspend () -> WeatherResult,
    /** In a downloaded area; otherwise a note says what's missing, with a way to download it. */
    downloaded: Boolean = true,
    onDownload: () -> Unit = {},
) {
    val uriHandler = LocalUriHandler.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val kind = info.kind?.let(::humanize)
            // A route pressed off any path (on a road, say) is named after the route.
            val routeName = info.routes.firstOrNull()?.name?.takeIf { info.kind == "route" }
            Text(
                text = info.name ?: routeName ?: kind ?: stringResource(R.string.feature_unnamed),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            if (info.name != null && kind != null) {
                Text(kind, style = MaterialTheme.typography.titleMedium)
            }
            if (!downloaded) NotDownloadedNote(onDownload)

            SelectionContainer {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    info.tags["ele"]?.let { ele ->
                        // OSM heights are in metres; anything unusual is shown as written.
                        Fact(stringResource(R.string.feature_elevation), ele.toDoubleOrNull()?.let(Format::elevation) ?: "$ele m")
                    }
                    info.sacGrade?.let { grade ->
                        val name = SacScale.entries.firstOrNull { it.grade == grade }?.osm?.let(::humanize)
                        Fact(stringResource(R.string.feature_difficulty), listOfNotNull("T$grade", name).joinToString(" · "))
                    }
                    info.visibility?.takeIf { it > 0 }?.let { level ->
                        TrailVisibility.entries.firstOrNull { it.level == level }?.let {
                            Fact(stringResource(R.string.feature_visibility), humanize(it.osm))
                        }
                    }
                    Fact(
                        stringResource(R.string.feature_coordinates),
                        Format.coordinates(info.position.latitude, info.position.longitude),
                    )
                }
            }

            WeatherSection(loadWeather)

            if (info.routes.isNotEmpty()) {
                HorizontalDivider()
                Text(stringResource(R.string.feature_routes), style = MaterialTheme.typography.titleSmall)
                for (route in info.routes) RouteFacts(route) { uriHandler.openUri(it) }
            }

            val tags = info.tags - "ele"
            if (tags.isNotEmpty()) {
                HorizontalDivider()
                Text(stringResource(R.string.feature_osm_tags), style = MaterialTheme.typography.titleSmall)
                SelectionContainer {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        for ((key, value) in tags) Fact(key, value)
                    }
                }
            }

            OutlinedButton(
                onClick = onGoTo,
                modifier = Modifier.fillMaxWidth().heightIn(min = TouchTarget),
            ) { Text(stringResource(R.string.feature_go_to)) }
            OutlinedButton(
                onClick = onSaveAsPlace,
                modifier = Modifier.fillMaxWidth().heightIn(min = TouchTarget),
            ) { Text(stringResource(R.string.feature_save_place)) }
            info.osmId?.let { id ->
                OutlinedButton(
                    onClick = { uriHandler.openUri("https://www.openstreetmap.org/$id") },
                    modifier = Modifier.fillMaxWidth().heightIn(min = TouchTarget),
                ) { Text(stringResource(R.string.feature_open_osm)) }
            }
            Button(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth().heightIn(min = TouchTarget),
            ) { Text(stringResource(R.string.feature_close)) }
        }
    }
}

/** A route running here: its waymark and name, what it's part of, and what OSM says about it. */
@Composable
private fun RouteFacts(route: MapRoute, onOpen: (String) -> Unit) {
    val density = LocalDensity.current.density
    val mark = remember(route.mark, density) {
        route.mark?.let { RouteMarks.image(RouteMarks.PREFIX + it, density)?.asImageBitmap() }
    }
    val facts = route.facts
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            mark?.let { Image(it, contentDescription = null, modifier = Modifier.size(32.dp)) }
            Column {
                Text(
                    route.name ?: facts["ref"] ?: stringResource(R.string.feature_route),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                for (whole in route.partOf) {
                    Text(stringResource(R.string.feature_part_of, whole), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        SelectionContainer {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                listOfNotNull(facts["from"], facts["to"]).takeIf { it.isNotEmpty() }?.let {
                    Fact(stringResource(R.string.feature_route_from_to), it.joinToString(" → "))
                }
                facts["distance"]?.let { d ->
                    // OSM route distances are in kilometres.
                    Fact(stringResource(R.string.feature_route_distance), d.toDoubleOrNull()?.let { Format.distance(it * 1000) } ?: d)
                }
                listOfNotNull(
                    facts["ascent"]?.let { "↑ " + (it.toDoubleOrNull()?.let(Format::elevation) ?: it) },
                    facts["descent"]?.let { "↓ " + (it.toDoubleOrNull()?.let(Format::elevation) ?: it) },
                ).takeIf { it.isNotEmpty() }?.let { Fact(stringResource(R.string.feature_route_climb), it.joinToString("  ")) }
                facts["duration:forward"]?.let { Fact(stringResource(R.string.feature_route_time), it.removePrefix("0") + " h") }
                facts["cai_scale"]?.let { Fact(stringResource(R.string.feature_route_cai), it) }
                facts["operator"]?.let { Fact(stringResource(R.string.feature_route_operator), it) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            facts["website"]?.takeIf { it.startsWith("http") }?.let { site ->
                OutlinedButton(onClick = { onOpen(site) }, modifier = Modifier.weight(1f).heightIn(min = TouchTarget)) {
                    Text(stringResource(R.string.feature_route_website))
                }
            }
            OutlinedButton(
                onClick = { onOpen("https://www.openstreetmap.org/${route.osmId}") },
                modifier = Modifier.weight(1f).heightIn(min = TouchTarget),
            ) { Text(stringResource(R.string.feature_route_osm)) }
        }
    }
}

@Composable
private fun Fact(label: String, value: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(0.4f))
        Text(value, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, modifier = Modifier.weight(0.6f))
    }
}

/** "demanding_mountain_hiking" → "Demanding mountain hiking". OSM values aren't translated yet. */
fun humanize(osm: String): String =
    osm.replace('_', ' ').replaceFirstChar { it.titlecase(Locale.getDefault()) }

/** Said where the details are looked for: without the area, some of them can't be shown. */
@Composable
private fun NotDownloadedNote(onDownload: () -> Unit) {
    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.feature_not_downloaded), style = MaterialTheme.typography.bodyMedium)
            OutlinedButton(onClick = onDownload, modifier = Modifier.fillMaxWidth().heightIn(min = TouchTarget)) {
                Text(stringResource(R.string.area_download))
            }
        }
    }
}
