package app.sentrek.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.sentrek.R
import app.sentrek.geo.overlay.SacScale
import app.sentrek.geo.overlay.TrailVisibility
import app.sentrek.map.MapFeatureInfo
import app.sentrek.ui.Format
import app.sentrek.ui.TouchTarget
import java.util.Locale

/** Everything we know about a long-pressed map feature. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeatureSheet(info: MapFeatureInfo, onSaveAsPlace: () -> Unit, onGoTo: () -> Unit, onDismiss: () -> Unit) {
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
            Text(
                text = info.name ?: kind ?: stringResource(R.string.feature_unnamed),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            if (info.name != null && kind != null) {
                Text(kind, style = MaterialTheme.typography.titleMedium)
            }

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
