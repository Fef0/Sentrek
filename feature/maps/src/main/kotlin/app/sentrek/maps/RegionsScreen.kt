package app.sentrek.maps

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.sentrek.feature.maps.R
import app.sentrek.geo.BoundingBox
import app.sentrek.geo.LatLon
import app.sentrek.geo.RegionCell
import app.sentrek.geo.RegionGrid
import app.sentrek.map.CellLook
import app.sentrek.map.MapStyle
import app.sentrek.map.RegionGridLayer
import app.sentrek.map.SentrekMap
import app.sentrek.ui.GloveTarget
import app.sentrek.ui.MapButton
import app.sentrek.ui.SentrekIcons
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import kotlin.math.roundToInt

/** Trekarta-style picker: tap grid squares, then download or delete them. */
@Composable
fun RegionsScreen(
    initialCamera: CameraPosition?,
    onBack: () -> Unit,
    viewModel: RegionsViewModel = hiltViewModel(),
) {
    val regions by viewModel.regions.collectAsStateWithLifecycle()
    val selection by viewModel.selection.collectAsStateWithLifecycle()
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var style by remember { mutableStateOf<Style?>(null) }
    var view by remember { mutableStateOf<BoundingBox?>(null) }

    Box(Modifier.fillMaxSize()) {
        SentrekMap(
            style = MapStyle.Hiking,
            modifier = Modifier.fillMaxSize(),
            onMapReady = { m, s ->
                initialCamera?.let { m.cameraPosition = it }
                RegionGridLayer.install(s)
                map = m
                style = s
            },
        )

        DisposableEffect(map) {
            val m = map ?: return@DisposableEffect onDispose {}
            val onIdle = MapLibreMap.OnCameraIdleListener { view = m.visibleBox() }
            val onClick = MapLibreMap.OnMapClickListener { point ->
                if (m.cameraPosition.zoom < RegionGridLayer.MIN_ZOOM) return@OnMapClickListener false
                viewModel.toggle(RegionGrid.cellAt(LatLon(point.latitude, point.longitude)))
                true
            }
            m.addOnCameraIdleListener(onIdle)
            m.addOnMapClickListener(onClick)
            view = m.visibleBox()
            onDispose {
                m.removeOnCameraIdleListener(onIdle)
                m.removeOnMapClickListener(onClick)
            }
        }

        LaunchedEffect(style, view, regions, selection) {
            val s = style ?: return@LaunchedEffect
            RegionGridLayer.update(s, view, cellLooks(regions, selection))
        }

        MapButton(
            icon = SentrekIcons.Back,
            contentDescription = stringResource(R.string.regions_back),
            onClick = onBack,
            modifier = Modifier.statusBarsPadding().padding(12.dp),
        )

        RegionsPanel(
            regions = regions,
            selection = selection,
            zoomedIn = view != null,
            onDownload = viewModel::downloadSelected,
            onDelete = viewModel::deleteSelected,
            onClear = viewModel::clearSelection,
            onRetryTrails = viewModel::retryTrails,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

/** Visible area, or null when zoomed out too far for the grid to be usable. */
private fun MapLibreMap.visibleBox(): BoundingBox? {
    if (cameraPosition.zoom < RegionGridLayer.MIN_ZOOM) return null
    val b = projection.visibleRegion.latLngBounds
    return BoundingBox(
        south = b.latitudeSouth.coerceIn(-90.0, 90.0),
        west = b.longitudeWest.coerceIn(-180.0, 180.0),
        north = b.latitudeNorth.coerceIn(-90.0, 90.0),
        east = b.longitudeEast.coerceIn(-180.0, 180.0),
    ).takeIf { it.west <= it.east }
}

private fun cellLooks(
    regions: Map<RegionCell, RegionState>,
    selection: Set<RegionCell>,
): Map<RegionCell, Pair<CellLook, String?>> {
    val looks = HashMap<RegionCell, Pair<CellLook, String?>>()
    for ((cell, state) in regions) {
        looks[cell] = when (val m = state.map) {
            MapDownload.Queued -> CellLook.Queued to null
            is MapDownload.Downloading -> CellLook.Downloading to "${(m.fraction * 100).roundToInt()}%"
            // A failed trail overlay is reported in the panel; the map itself is usable.
            is MapDownload.Ready -> CellLook.Ready to null
        }
    }
    for (cell in selection) looks[cell] = CellLook.Selected to looks[cell]?.second
    return looks
}

@Composable
private fun RegionsPanel(
    regions: Map<RegionCell, RegionState>,
    selection: Set<RegionCell>,
    zoomedIn: Boolean,
    onDownload: () -> Unit,
    onDelete: () -> Unit,
    onClear: () -> Unit,
    onRetryTrails: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val toDownload = selection.count { it !in regions }
    val toDelete = selection.count { it in regions }
    val pending = regions.values.count { it.map !is MapDownload.Ready }
    val trailsFailed = regions.values.count { it.trails == TrailsDownload.Failed }
    val storedBytes = regions.values.sumOf { (it.map as? MapDownload.Ready)?.bytes ?: 0L }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shadowElevation = 8.dp,
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier.navigationBarsPadding().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val status = when {
                selection.isNotEmpty() -> pluralStringResource(R.plurals.regions_selected, selection.size, selection.size)
                !zoomedIn -> stringResource(R.string.regions_zoom_in)
                pending > 0 -> pluralStringResource(R.plurals.regions_downloading, regions.size, regions.size - pending, regions.size)
                regions.isNotEmpty() ->
                    pluralStringResource(R.plurals.regions_stored, regions.size, regions.size, Formatter.formatShortFileSize(context, storedBytes))
                else -> stringResource(R.string.regions_hint)
            }
            Text(status, style = MaterialTheme.typography.titleMedium)

            if (selection.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (toDownload > 0) {
                        PanelButton(stringResource(R.string.regions_download, toDownload), onDownload, Modifier.weight(1f))
                    }
                    if (toDelete > 0) {
                        PanelButton(
                            stringResource(R.string.regions_delete, toDelete), onDelete, Modifier.weight(1f), destructive = true,
                        )
                    }
                    OutlinedButton(onClick = onClear, modifier = Modifier.heightIn(min = GloveTarget)) {
                        Text(stringResource(R.string.regions_clear))
                    }
                }
            } else if (trailsFailed > 0) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        pluralStringResource(R.plurals.regions_trails_failed, trailsFailed, trailsFailed),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    OutlinedButton(onClick = onRetryTrails, modifier = Modifier.heightIn(min = GloveTarget)) {
                        Text(stringResource(R.string.regions_retry))
                    }
                }
            }
        }
    }
}

@Composable
private fun PanelButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, destructive: Boolean = false) {
    Button(
        onClick = onClick,
        modifier = modifier.heightIn(min = GloveTarget),
        colors = if (destructive) {
            ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
        } else {
            ButtonDefaults.buttonColors()
        },
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}
