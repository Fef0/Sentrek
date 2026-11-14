package app.sentrek.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.sentrek.R
import app.sentrek.geo.CoordinateGrid
import app.sentrek.geo.overlay.MapKind
import app.sentrek.ui.FitText
import app.sentrek.ui.SentrekIcons
import app.sentrek.ui.TouchTarget

/** What the map layers sheet switches, as in Settings › Map. */
class MapLayers(
    val difficulty: Boolean,
    val contours: Boolean,
    val hillshade: Boolean,
    val slopes: Boolean,
    val points: Boolean,
    val grid: CoordinateGrid.Kind = CoordinateGrid.Kind.Off,
)

/**
 * The map button's sheet: hiking, cycling or winter map, and the layers drawn on it. Each
 * change applies at once, the sheet stays open to see it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapLayersSheet(
    kind: MapKind,
    onKind: (MapKind) -> Unit,
    layers: MapLayers,
    onDifficulty: (Boolean) -> Unit,
    onContours: (Boolean) -> Unit,
    onHillshade: (Boolean) -> Unit,
    onSlopes: (Boolean) -> Unit,
    onPoints: (Boolean) -> Unit,
    onGrid: (CoordinateGrid.Kind) -> Unit,
    /** Opens the map downloads: where the map is looked for, not in a menu of its own. */
    onDownloadMaps: () -> Unit,
    onLegend: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        // Never quite the screen's height: a sheet just as tall as the room jumped and
        // flickered when dragged down (it went from fitting to scrolling under the finger).
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = (LocalConfiguration.current.screenHeightDp * MAX_HEIGHT).dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            for (k in MapKind.entries) {
                KindRow(k, selected = k == kind, onClick = { onKind(k) })
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = TouchTarget)
                    .clickable(onClick = onDownloadMaps)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Icon(SentrekIcons.Download, contentDescription = null)
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.map_download_title), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.map_download_hint), style = MaterialTheme.typography.bodyMedium)
                }
                Icon(SentrekIcons.ChevronRight, contentDescription = null)
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Text(stringResource(R.string.map_layers_title), style = MaterialTheme.typography.titleSmall)
            // The difficulty colours are for hiking: the other maps hide them. The row stays,
            // so the sheet doesn't change height under the finger when the map changes.
            LayerRow(stringResource(R.string.settings_trail_difficulty), layers.difficulty, onDifficulty, enabled = kind == MapKind.Hiking)
            LayerRow(stringResource(R.string.settings_contours), layers.contours, onContours)
            LayerRow(stringResource(R.string.settings_hillshade), layers.hillshade, onHillshade)
            LayerRow(stringResource(R.string.settings_slopes), layers.slopes, onSlopes)
            LayerRow(stringResource(R.string.settings_map_points), layers.points, onPoints)
            Text(stringResource(R.string.grid_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
            val options = listOf(
                CoordinateGrid.Kind.Off to stringResource(R.string.grid_off),
                CoordinateGrid.Kind.Utm to stringResource(R.string.grid_utm),
                CoordinateGrid.Kind.LatLon to stringResource(R.string.grid_lat_lon),
            )
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                options.forEachIndexed { i, (option, label) ->
                    SegmentedButton(
                        selected = option == layers.grid,
                        onClick = { onGrid(option) },
                        shape = SegmentedButtonDefaults.itemShape(i, options.size),
                        modifier = Modifier.heightIn(min = TouchTarget),
                        icon = {},
                    ) { FitText(label) }
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = TouchTarget)
                    .clickable(onClick = onLegend)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Icon(SentrekIcons.Info, contentDescription = null)
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.legend_open), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.legend_open_hint), style = MaterialTheme.typography.bodyMedium)
                }
                Icon(SentrekIcons.ChevronRight, contentDescription = null)
            }
        }
    }
}

/** Of the screen's height, at most: the rest scrolls. */
private const val MAX_HEIGHT = 0.8f

@Composable
private fun KindRow(kind: MapKind, selected: Boolean, onClick: () -> Unit) {
    val (icon, name, hint) = when (kind) {
        MapKind.Hiking -> Triple(SentrekIcons.Hiking, R.string.map_kind_hiking, R.string.map_kind_hiking_hint)
        MapKind.Cycling -> Triple(SentrekIcons.Cycling, R.string.map_kind_cycling, R.string.map_kind_cycling_hint)
        MapKind.Winter -> Triple(SentrekIcons.Skiing, R.string.map_kind_winter, R.string.map_kind_winter_hint)
    }
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = TouchTarget)
            .background(
                if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
                RoundedCornerShape(12.dp),
            )
            .selectable(selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(icon, contentDescription = null)
        Column(Modifier.weight(1f)) {
            Text(stringResource(name), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(hint), style = MaterialTheme.typography.bodyMedium)
        }
        RadioButton(selected = selected, onClick = null)
    }
}

@Composable
private fun LayerRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean = true) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = TouchTarget)
            .toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
            .padding(vertical = 4.dp)
            .alpha(if (enabled) 1f else 0.4f),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        // The whole row toggles; the switch only shows the state.
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}
