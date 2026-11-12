package app.sentrek.maps

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.text.format.Formatter
import java.text.DateFormat
import java.util.Date
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.core.content.ContextCompat
import app.sentrek.map.MapOverlays
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLngBounds
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
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
import app.sentrek.geo.overlay.MapKind
import androidx.compose.material3.FilterChip
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import app.sentrek.map.CellLook
import app.sentrek.map.MapStyle
import app.sentrek.map.RegionGridLayer
import app.sentrek.map.SentrekMap
import app.sentrek.ui.Confirmation
import app.sentrek.ui.ConfirmDialog
import app.sentrek.ui.TouchTarget
import app.sentrek.ui.MapButton
import app.sentrek.map.MapHeights
import app.sentrek.ui.Format
import app.sentrek.ui.MapCredit
import app.sentrek.ui.safeTopPadding
import app.sentrek.ui.SentrekIcons
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import kotlin.math.roundToInt

/** Trekarta-style picker: tap grid squares, then download or delete them; import map files. */
@Composable
fun RegionsScreen(
    initialCamera: CameraPosition?,
    onBack: () -> Unit,
    /** Opened from a "not downloaded" note: the area here comes up selected, ready to download. */
    select: LatLon? = null,
    viewModel: RegionsViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val regions by viewModel.regions.collectAsStateWithLifecycle()
    val selection by viewModel.selection.collectAsStateWithLifecycle()
    val imported by viewModel.imported.collectAsStateWithLifecycle()
    val importing by viewModel.importing.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val status by viewModel.status.collectAsStateWithLifecycle()
    val flyTo by viewModel.flyTo.collectAsStateWithLifecycle()
    val extras by viewModel.extras.collectAsStateWithLifecycle()
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var style by remember { mutableStateOf<Style?>(null) }
    var view by remember { mutableStateOf<BoundingBox?>(null) }
    var showImported by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<Confirmation?>(null) }

    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::import)
    }
    // Without it the download still runs, only the progress notification is hidden.
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    // On mobile data the user hasn't allowed in Settings: asked before anything is fetched.
    val overMobile = { action: () -> Unit ->
        if (viewModel.onMobileData()) {
            confirm = Confirmation(
                title = context.getString(R.string.mobile_data_title),
                text = context.getString(R.string.mobile_data_text),
                confirmLabel = context.getString(R.string.mobile_data_download),
                onConfirm = action,
            )
        } else {
            action()
        }
    }
    val download = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        overMobile(viewModel::downloadSelected)
    }

    Box(Modifier.fillMaxSize()) {
        SentrekMap(
            style = MapStyle.Hiking,
            modifier = Modifier.fillMaxSize(),
            onMapReady = { m, s ->
                initialCamera?.let { m.cameraPosition = it }
                RegionGridLayer.install(s)
                MapHeights.apply(s, Format.units.elevation)
                map = m
                style = s
            },
        )

        LaunchedEffect(map, select) {
            val m = map ?: return@LaunchedEffect
            val cell = RegionGrid.cellAt(select ?: return@LaunchedEffect)
            if (cell !in selection && cell !in regions) viewModel.toggle(cell)
            val box = cell.bbox
            m.cameraPosition = CameraUpdateFactory.newLatLngBounds(LatLngBounds.from(box.north, box.east, box.south, box.west), SELECT_PADDING_PX)
                .getCameraPosition(m) ?: return@LaunchedEffect
        }

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

        val labels = CellLabels(
            queued = stringResource(R.string.regions_cell_queued),
            map = stringResource(R.string.regions_cell_map),
            trailsWaiting = stringResource(R.string.regions_cell_trails_waiting),
            trails = stringResource(R.string.regions_cell_trails),
            busy = stringResource(R.string.regions_cell_busy),
            pistes = stringResource(R.string.regions_cell_pistes),
            cycling = stringResource(R.string.regions_cell_cycling),
        )
        LaunchedEffect(style, view, regions, selection) {
            val s = style ?: return@LaunchedEffect
            RegionGridLayer.update(s, view, cellLooks(regions, selection, labels))
        }

        LaunchedEffect(style, imported) {
            style?.let { MapOverlays.sync(it, imported.map(ImportedMap::overlay), emptyMap(), showDifficulty = false) }
        }

        LaunchedEffect(map, flyTo) {
            val m = map ?: return@LaunchedEffect
            val box = flyTo ?: return@LaunchedEffect
            m.animateCamera(CameraUpdateFactory.newLatLngBounds(LatLngBounds.from(box.north, box.east, box.south, box.west), 80))
            viewModel.flyToHandled()
        }

        MapButton(
            icon = SentrekIcons.Back,
            contentDescription = stringResource(R.string.regions_back),
            onClick = onBack,
            modifier = Modifier.safeTopPadding().padding(12.dp),
        )
        MapCredit(Modifier.align(Alignment.TopEnd).safeTopPadding().padding(12.dp))

        RegionsPanel(
            regions = regions,
            selection = selection,
            zoomedIn = view != null,
            importedCount = imported.size,
            importing = importing,
            message = message,
            status = status,
            estimate = viewModel::estimate,
            extras = extras,
            onToggleExtra = viewModel::toggleExtra,
            onSaveExtras = { if (viewModel.extrasToDownload()) overMobile(viewModel::saveExtras) else viewModel.saveExtras() },
            onDownload = download,
            onDelete = {
                val count = selection.count { it in regions }
                confirm = Confirmation(
                    title = context.resources.getQuantityString(R.plurals.confirm_delete_regions_title, count, count),
                    text = context.getString(R.string.confirm_delete_regions_text),
                    confirmLabel = context.getString(R.string.confirm_delete),
                    destructive = true,
                    onConfirm = viewModel::deleteSelected,
                )
            },
            onClear = viewModel::clearSelection,
            onStop = {
                // Asked first: a stray tap mustn't throw away a long download.
                confirm = Confirmation(
                    title = context.getString(R.string.confirm_stop_downloads_title),
                    text = context.getString(R.string.confirm_stop_downloads_text),
                    confirmLabel = context.getString(R.string.confirm_stop_downloads),
                    destructive = true,
                    onConfirm = viewModel::stopDownloads,
                )
            },
            onUpdate = { overMobile(viewModel::updateSelected) },
            onUpdateOld = { overMobile(viewModel::updateOld) },
            onRetryTrails = { overMobile(viewModel::retryTrails) },
            onImport = { pickFile.launch(arrayOf("*/*")) },
            onShowImported = { showImported = true },
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }

    if (showImported) {
        ImportedMapsDialog(
            maps = imported,
            onShow = { viewModel.showImported(it); showImported = false },
            onDelete = { map ->
                confirm = Confirmation(
                    title = context.getString(R.string.confirm_delete_imported_title),
                    text = context.getString(R.string.confirm_delete_imported_text, map.name),
                    confirmLabel = context.getString(R.string.confirm_delete),
                    destructive = true,
                    onConfirm = { viewModel.deleteImported(map) },
                )
            },
            onDismiss = { showImported = false },
        )
    }
    confirm?.let { ConfirmDialog(it) { confirm = null } }
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

/** What each area on the grid says while it downloads; [map] takes the percentage. */
private class CellLabels(
    val queued: String,
    val map: String,
    val trailsWaiting: String,
    val trails: String,
    val busy: String,
    val pistes: String,
    val cycling: String,
)

private fun cellLooks(
    regions: Map<RegionCell, RegionState>,
    selection: Set<RegionCell>,
    labels: CellLabels,
): Map<RegionCell, Pair<CellLook, String?>> {
    val looks = HashMap<RegionCell, Pair<CellLook, String?>>()
    for ((cell, state) in regions) {
        looks[cell] = when (val m = state.map) {
            MapDownload.Queued -> CellLook.Queued to labels.queued
            // The area's own share of the batch's percentage: with one area, the same number.
            is MapDownload.Downloading -> CellLook.Downloading to String.format(labels.map, (state.progress() * 100).toInt().coerceAtMost(99))
            // Green only once the hiking details (trails, huts, springs…) are there too, and
            // the pistes or cycle routes asked for.
            is MapDownload.Ready -> {
                val extra = state.extras.entries.firstOrNull { it.value != TrailsDownload.Ready }
                when {
                    state.complete -> CellLook.Ready to null
                    TrailsDownload.Failed in state.details -> CellLook.Failed to null
                    state.trails == TrailsDownload.Missing || state.trails == TrailsDownload.Waiting -> CellLook.Downloading to labels.trailsWaiting
                    state.trails == TrailsDownload.Downloading -> CellLook.Downloading to labels.trails
                    TrailsDownload.Retrying in state.details -> CellLook.Downloading to labels.busy
                    extra?.key == MapKind.Winter -> CellLook.Downloading to labels.pistes
                    else -> CellLook.Downloading to labels.cycling
                }
            }
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
    importedCount: Int,
    importing: Boolean,
    @StringRes message: Int?,
    /** What is downloading now, null when nothing is. */
    status: DownloadStatus?,
    /** About how many bytes downloading these areas takes, and the space left. */
    estimate: (Collection<RegionCell>) -> Pair<Long, Long>,
    /** The extra maps chosen for the selected areas. */
    extras: Set<MapKind>,
    onToggleExtra: (MapKind) -> Unit,
    onSaveExtras: () -> Unit,
    onDownload: () -> Unit,
    onDelete: () -> Unit,
    onClear: () -> Unit,
    onStop: () -> Unit,
    onUpdate: () -> Unit,
    onUpdateOld: () -> Unit,
    onRetryTrails: () -> Unit,
    onImport: () -> Unit,
    onShowImported: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val toDownload = selection.count { it !in regions }
    val toDelete = selection.count { it in regions }
    val trailsFailed = regions.values.count { it.trails == TrailsDownload.Failed }
    val extrasFailed = regions.values.count { TrailsDownload.Failed in it.extras.values }
    // Selected areas on the phone whose extra maps aren't the ones chosen.
    val extrasChanged = selection.any { cell -> regions[cell]?.let { it.extras.keys != extras } == true }
    val storedBytes = regions.values.sumOf { (it.map as? MapDownload.Ready)?.bytes ?: 0L }
    val now = System.currentTimeMillis()
    val old = regions.values.count { it.isOld(now) }
    val selectedDate = selection.mapNotNull { regions[it]?.updatedAt }.minOrNull()

    Surface(
        modifier = modifier.fillMaxWidth(),
        shadowElevation = 8.dp,
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier.navigationBarsPadding().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val title = when {
                selection.isNotEmpty() -> pluralStringResource(R.plurals.regions_selected, selection.size, selection.size)
                importing -> stringResource(R.string.import_running)
                // Before "zoom in": the download is what the user came back to see.
                status != null -> pluralStringResource(R.plurals.download_title, status.areas, status.areas) + " · ${status.percent}%"
                !zoomedIn -> stringResource(R.string.regions_zoom_in)
                regions.isNotEmpty() ->
                    pluralStringResource(R.plurals.regions_stored, regions.size, regions.size, Formatter.formatShortFileSize(context, storedBytes))
                else -> stringResource(R.string.regions_hint)
            }
            Text(title, style = MaterialTheme.typography.titleMedium)

            message?.let {
                Text(stringResource(it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyLarge)
            }
            if (status != null && selection.isEmpty()) DownloadProgress(status, onStop)

            if (selection.isNotEmpty()) {
                val new = selection.filter { it !in regions }
                if (new.isNotEmpty()) {
                    val (needed, free) = remember(new) { estimate(new) }
                    val size = Formatter.formatShortFileSize(context, needed)
                    val left = Formatter.formatShortFileSize(context, free)
                    if (needed > free) {
                        Text(
                            stringResource(R.string.regions_no_space, size, left),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    } else {
                        Text(stringResource(R.string.regions_size, size, left), style = MaterialTheme.typography.bodyLarge)
                    }
                }
                selectedDate?.let { date ->
                    val day = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(date))
                    val bytes = selection.sumOf { (regions[it]?.map as? MapDownload.Ready)?.bytes ?: 0L }
                    val size = Formatter.formatShortFileSize(context, bytes)
                    Text(stringResource(R.string.regions_downloaded_on, size, day), style = MaterialTheme.typography.bodyLarge)
                }
                MapKinds(extras, onToggleExtra)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    // Three buttons at most, wide enough for gloves: new areas first.
                    if (toDownload > 0) {
                        PanelButton(stringResource(R.string.regions_download, toDownload), onDownload, Modifier.weight(1f))
                    } else if (extrasChanged) {
                        PanelButton(stringResource(R.string.regions_save, toDelete), onSaveExtras, Modifier.weight(1f))
                    } else if (toDelete > 0) {
                        PanelButton(stringResource(R.string.regions_update, toDelete), onUpdate, Modifier.weight(1f))
                    }
                    if (toDelete > 0) {
                        PanelButton(
                            stringResource(R.string.regions_delete, toDelete), onDelete, Modifier.weight(1f), destructive = true,
                        )
                    }
                    OutlinedButton(onClick = onClear, modifier = Modifier.heightIn(min = TouchTarget)) {
                        Text(stringResource(R.string.regions_clear))
                    }
                }
                return@Column
            }

            if (old > 0 && status == null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        pluralStringResource(R.plurals.regions_old, old, old),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    OutlinedButton(onClick = onUpdateOld, modifier = Modifier.heightIn(min = TouchTarget)) {
                        Text(stringResource(R.string.regions_update_old))
                    }
                }
            }

            if (trailsFailed > 0 || extrasFailed > 0) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        if (trailsFailed > 0) {
                            pluralStringResource(R.plurals.regions_trails_failed, trailsFailed, trailsFailed)
                        } else {
                            pluralStringResource(R.plurals.regions_extras_failed, extrasFailed, extrasFailed)
                        },
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    OutlinedButton(onClick = onRetryTrails, modifier = Modifier.heightIn(min = TouchTarget)) {
                        Text(stringResource(R.string.regions_retry))
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = onImport,
                    enabled = !importing,
                    modifier = Modifier.weight(1f).heightIn(min = TouchTarget),
                ) {
                    Text(stringResource(R.string.regions_import))
                }
                if (importedCount > 0) {
                    OutlinedButton(
                        onClick = onShowImported,
                        modifier = Modifier.weight(1f).heightIn(min = TouchTarget),
                    ) {
                        Text(pluralStringResource(R.plurals.regions_imported, importedCount, importedCount))
                    }
                }
            }
        }
    }
}

@Composable
private fun ImportedMapsDialog(
    maps: List<ImportedMap>,
    onShow: (ImportedMap) -> Unit,
    onDelete: (ImportedMap) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.imported_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                for (map in maps) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(map.name, style = MaterialTheme.typography.titleMedium)
                        Text(
                            stringResource(
                                R.string.imported_zooms, map.info.minZoom, map.info.maxZoom,
                                Formatter.formatShortFileSize(context, map.file.length()),
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            if (map.info.bounds != null) {
                                OutlinedButton(onClick = { onShow(map) }, modifier = Modifier.heightIn(min = TouchTarget)) {
                                    Text(stringResource(R.string.imported_show))
                                }
                            }
                            OutlinedButton(onClick = { onDelete(map) }, modifier = Modifier.heightIn(min = TouchTarget)) {
                                Text(stringResource(R.string.imported_delete), color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = TouchTarget)) {
                Text(stringResource(R.string.imported_close))
            }
        },
    )
}

/** Which maps the selected areas get: hiking always, cycling and winter if chosen. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MapKinds(extras: Set<MapKind>, onToggle: (MapKind) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.regions_maps_label), style = MaterialTheme.typography.titleSmall)
            for (kind in MapKind.entries) {
                val extra = kind in MapKind.extras
                val selected = !extra || kind in extras
                FilterChip(
                    selected = selected,
                    // Hiking always comes: drawn ticked like the others chosen, not greyed out as
                    // if it were off. A tap on it does nothing.
                    onClick = { if (extra) onToggle(kind) },
                    label = { Text(stringResource(kindName(kind))) },
                    // A tick: the filled look alone is hard to tell apart in the dark theme.
                    leadingIcon = if (selected) {
                        { androidx.compose.material3.Icon(SentrekIcons.Check, contentDescription = null, modifier = Modifier.size(18.dp)) }
                    } else {
                        null
                    },
                    modifier = Modifier.heightIn(min = TouchTarget),
                )
            }
        }
        Text(
            stringResource(R.string.regions_kinds_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun kindName(kind: MapKind) = when (kind) {
    MapKind.Hiking -> R.string.regions_kind_hiking
    MapKind.Cycling -> R.string.regions_kind_cycling
    MapKind.Winter -> R.string.regions_kind_winter
}

@Composable
private fun PanelButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, destructive: Boolean = false) {
    Button(
        onClick = onClick,
        modifier = modifier.heightIn(min = TouchTarget),
        colors = if (destructive) {
            ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
        } else {
            ButtonDefaults.buttonColors()
        },
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

/**
 * What the download is doing now, for the whole batch: a bar, how many areas are done, and the
 * step under way (the map of one area, then its trail details, which come from another server,
 * one area at a time) or what it waits for.
 */
@Composable
private fun DownloadProgress(status: DownloadStatus, onStop: () -> Unit) {
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        LinearProgressIndicator(
            progress = { status.fraction },
            modifier = Modifier.fillMaxWidth().height(6.dp),
            drawStopIndicator = {},
        )
        Text(
            DownloadNotifications.statusLine(context, status),
            style = MaterialTheme.typography.bodyLarge,
            color = if (status.waiting == DownloadStatus.Waiting.Network) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        )
        val trails = status.step != DownloadStatus.Step.Map && status.step != DownloadStatus.Step.Queued
        if (trails || status.waiting == DownloadStatus.Waiting.Server) {
            Text(
                stringResource(R.string.regions_trails_explain),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // Leaving is fine: said here, where people wait for it to end.
        Text(
            stringResource(R.string.regions_leave_ok),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(onClick = onStop, modifier = Modifier.fillMaxWidth().heightIn(min = TouchTarget)) {
            Text(stringResource(R.string.regions_stop_downloads), color = MaterialTheme.colorScheme.error)
        }
    }
}

/** Room around the area opened from a note, so the squares next to it show too. */
private const val SELECT_PADDING_PX = 160
