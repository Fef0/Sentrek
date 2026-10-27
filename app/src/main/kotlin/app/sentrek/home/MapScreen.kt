package app.sentrek.home

import android.Manifest
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import app.sentrek.R
import app.sentrek.geo.BoundingBox
import app.sentrek.map.MapFeatureInfo
import app.sentrek.map.MapFeatures
import app.sentrek.map.MapLocation
import app.sentrek.map.MapOverlays
import app.sentrek.map.MapStyle
import app.sentrek.map.SentrekMap
import app.sentrek.map.TrackLayers
import app.sentrek.record.TrackNameDialog
import app.sentrek.ui.BarItem
import app.sentrek.ui.BottomBar
import app.sentrek.ui.LocalTouch
import app.sentrek.ui.TouchTarget
import app.sentrek.ui.MapButton
import app.sentrek.ui.SentrekIcons
import kotlinx.coroutines.flow.Flow
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style

// Western Alps until "remember last position" lands with DataStore.
val DefaultCamera: CameraPosition = CameraPosition.Builder()
    .target(LatLng(45.9763, 7.6586))
    .zoom(11.0)
    .build()

/** What a location permission request was for, to carry on once granted. */
private enum class AfterPermission { Follow, Record }

@Composable
fun MapScreen(
    camera: CameraPosition,
    onCameraChange: (CameraPosition) -> Unit,
    onOpenMaps: () -> Unit,
    onOpenTracks: () -> Unit,
    onOpenSettings: () -> Unit,
    focus: BoundingBox?,
    onFocusDone: () -> Unit,
    /** +1 / -1 zoom steps from the volume keys (glove mode). */
    zoomKeys: Flow<Int>,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val overlays by viewModel.overlays.collectAsStateWithLifecycle()
    val showDifficulty by viewModel.showDifficulty.collectAsStateWithLifecycle()
    val rasters by viewModel.rasters.collectAsStateWithLifecycle()
    val recording by viewModel.recording.collectAsStateWithLifecycle()
    val shownTracks by viewModel.shownTracks.collectAsStateWithLifecycle()
    val currentOnCameraChange by rememberUpdatedState(onCameraChange)
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var style by remember { mutableStateOf<Style?>(null) }
    var pressed by remember { mutableStateOf<MapFeatureInfo?>(null) }
    var hasLocation by remember { mutableStateOf(viewModel.hasLocationPermission()) }
    var following by remember { mutableStateOf(false) }
    var askStop by remember { mutableStateOf(false) }
    var askDiscard by remember { mutableStateOf(false) }
    var askName by remember { mutableStateOf(false) }
    var afterPermission by remember { mutableStateOf<AfterPermission?>(null) }
    val haptics = LocalHapticFeedback.current
    val pressRadiusPx = with(LocalDensity.current) { LocalTouch.current.pressRadius.toPx() }

    // The user may grant or revoke location in system settings while we're in the background.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { hasLocation = viewModel.hasLocationPermission() }

    // Applied by an effect below, once the position marker exists (right after a permission grant it doesn't yet).
    val follow = { following = true }
    val startRecording = {
        viewModel.startRecording()
        follow()
    }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        hasLocation = viewModel.hasLocationPermission()
        val next = afterPermission
        afterPermission = null
        when {
            !hasLocation -> if (result[Manifest.permission.ACCESS_COARSE_LOCATION] == true) {
                // Android 12+ lets the user pick "approximate": useless for a hiking track.
                Toast.makeText(context, R.string.location_precise_needed, Toast.LENGTH_LONG).show()
            }
            next == AfterPermission.Record -> startRecording()
            next == AfterPermission.Follow -> follow()
        }
    }
    val withLocation = { action: AfterPermission, run: () -> Unit ->
        if (hasLocation) {
            run()
        } else {
            afterPermission = action
            val asked = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            // Recording shows a notification; without it Android hides it but records anyway.
            if (action == AfterPermission.Record && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                asked += Manifest.permission.POST_NOTIFICATIONS
            }
            permissions.launch(asked.toTypedArray())
        }
    }

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f)) {
            SentrekMap(
                style = MapStyle.Hiking,
                modifier = Modifier.fillMaxSize(),
                onMapReady = { m, s ->
                    m.cameraPosition = camera
                    map = m
                    style = s
                },
            )

            recording?.let {
                RecordingStats(
                    recording = it,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .statusBarsPadding()
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                        .padding(end = TouchTarget) // Keep clear of the compass.
                        .fillMaxWidth(),
                )
            }

            // Map controls stay on the right edge, in the lower half: reachable with the thumb.
            Column(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 12.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                MapButton(
                    icon = SentrekIcons.MyLocation,
                    contentDescription = stringResource(R.string.my_location),
                    onClick = { withLocation(AfterPermission.Follow) { follow() } },
                    active = following,
                )
                MapButton(
                    icon = SentrekIcons.Plus,
                    contentDescription = stringResource(R.string.zoom_in),
                    onClick = { map?.animateCamera(CameraUpdateFactory.zoomIn()) },
                )
                MapButton(
                    icon = SentrekIcons.Minus,
                    contentDescription = stringResource(R.string.zoom_out),
                    onClick = { map?.animateCamera(CameraUpdateFactory.zoomOut()) },
                )
            }
        }

        BottomBar(
            items = listOf(
                BarItem(SentrekIcons.Layers, stringResource(R.string.bar_maps), onOpenMaps),
                BarItem(SentrekIcons.Tracks, stringResource(R.string.bar_tracks), onOpenTracks),
                if (recording?.isPaused == true) {
                    BarItem(SentrekIcons.Pause, stringResource(R.string.bar_paused), { askStop = true }, highlighted = true)
                } else if (recording != null) {
                    BarItem(SentrekIcons.Stop, stringResource(R.string.bar_stop), { askStop = true }, highlighted = true)
                } else {
                    BarItem(SentrekIcons.Record, stringResource(R.string.bar_record), {
                        withLocation(AfterPermission.Record) { startRecording() }
                    })
                },
                BarItem(Icons.Filled.Settings, stringResource(R.string.bar_settings), onOpenSettings),
            ),
        )
    }

    DisposableEffect(map) {
        val m = map ?: return@DisposableEffect onDispose {}
        val onIdle = MapLibreMap.OnCameraIdleListener { currentOnCameraChange(m.cameraPosition) }
        m.addOnCameraIdleListener(onIdle)
        onDispose { m.removeOnCameraIdleListener(onIdle) }
    }

    DisposableEffect(map) {
        val m = map ?: return@DisposableEffect onDispose {}
        val onLongPress = MapLibreMap.OnMapLongClickListener { latLng ->
            val info = MapFeatures.at(m, m.projection.toScreenLocation(latLng), latLng, pressRadiusPx)
                ?: return@OnMapLongClickListener false
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            pressed = info
            true
        }
        m.addOnMapLongClickListener(onLongPress)
        onDispose { m.removeOnMapLongClickListener(onLongPress) }
    }

    // Position on the map while the app is visible; the GPS is off otherwise (unless recording).
    LaunchedEffect(map, style, hasLocation) {
        val m = map ?: return@LaunchedEffect
        val s = style ?: return@LaunchedEffect
        if (!hasLocation) return@LaunchedEffect
        MapLocation.activate(context, m, s)
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            viewModel.locations().collect { MapLocation.update(m, it) }
        }
    }

    LaunchedEffect(map, style, hasLocation, following) {
        val m = map ?: return@LaunchedEffect
        val s = style ?: return@LaunchedEffect
        if (!hasLocation || !following) return@LaunchedEffect
        MapLocation.activate(context, m, s)
        MapLocation.follow(m) { following = false }
    }

    LaunchedEffect(map, zoomKeys) {
        val m = map ?: return@LaunchedEffect
        zoomKeys.collect { step ->
            m.animateCamera(if (step > 0) CameraUpdateFactory.zoomIn() else CameraUpdateFactory.zoomOut())
        }
    }

    LaunchedEffect(map, focus) {
        val m = map ?: return@LaunchedEffect
        val box = focus ?: return@LaunchedEffect
        m.animateCamera(CameraUpdateFactory.newLatLngBounds(LatLngBounds.from(box.north, box.east, box.south, box.west), 120))
        onFocusDone()
    }

    pressed?.let { FeatureSheet(it, onDismiss = { pressed = null }) }

    val current = recording
    if (askStop && current != null) {
        RecordingDialog(
            paused = current.isPaused,
            onPauseResume = {
                askStop = false
                if (current.isPaused) viewModel.resumeRecording() else viewModel.pauseRecording()
            },
            onSave = { askStop = false; askName = true },
            onDiscard = { askStop = false; askDiscard = true },
            onDismiss = { askStop = false },
        )
    }
    if (askName && current != null) {
        TrackNameDialog(
            title = stringResource(R.string.record_save),
            confirmLabel = stringResource(R.string.record_save_confirm),
            initial = viewModel.defaultTrackName(current),
            onConfirm = { name ->
                askName = false
                viewModel.saveRecording(name) { saved ->
                    if (saved != null) Toast.makeText(context, context.getString(R.string.record_saved, saved.name), Toast.LENGTH_LONG).show()
                }
            },
            onDismiss = { askName = false },
        )
    }
    if (askDiscard) {
        AlertDialog(
            onDismissRequest = { askDiscard = false },
            title = { Text(stringResource(R.string.record_discard_title)) },
            text = { Text(stringResource(R.string.record_discard_text)) },
            confirmButton = {
                Button(
                    onClick = { askDiscard = false; viewModel.discardRecording() },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) { Text(stringResource(R.string.record_discard)) }
            },
            dismissButton = {
                TextButton(onClick = { askDiscard = false }) { Text(stringResource(R.string.record_continue)) }
            },
        )
    }

    LaunchedEffect(style, rasters, overlays, showDifficulty) {
        style?.let { MapOverlays.sync(it, rasters, overlays, showDifficulty) }
    }

    LaunchedEffect(style, recording?.segments, shownTracks) {
        style?.let { TrackLayers.sync(it, recording?.segments.orEmpty(), shownTracks.values) }
    }
}
