package app.sentrek.home

import androidx.activity.compose.BackHandler
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.animation.fadeOut
import androidx.compose.animation.fadeIn
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.draw.alpha
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.AnimatedVisibility
import android.graphics.PointF
import android.view.MotionEvent
import android.Manifest
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
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
import app.sentrek.feature.planner.R as PlannerR
import app.sentrek.feature.record.R as RecordR
import app.sentrek.data.PowerMode
import app.sentrek.geo.BoundingBox
import app.sentrek.geo.Geo
import app.sentrek.geo.LatLon
import app.sentrek.geo.RegionCell
import app.sentrek.geo.RegionGrid
import app.sentrek.geo.place.Place
import app.sentrek.geo.place.PlaceKind
import app.sentrek.map.LocalNightMap
import app.sentrek.map.FollowMode
import app.sentrek.map.MapFeatureInfo
import app.sentrek.map.MapFeatures
import app.sentrek.map.MapLocation
import app.sentrek.map.MapContours
import app.sentrek.map.MapHeights
import app.sentrek.map.MapOverlays
import app.sentrek.map.MapStyle
import app.sentrek.map.PlaceLayers
import app.sentrek.map.MapTrails
import app.sentrek.map.PlanLayers
import app.sentrek.planner.DiscardPlanDialog
import app.sentrek.planner.PlanGestures
import app.sentrek.planner.PlanHandle
import app.sentrek.planner.PlanPanel
import app.sentrek.planner.PlannerViewModel
import app.sentrek.planner.TrailSource
import app.sentrek.map.SentrekMap
import app.sentrek.map.TrackLayers
import app.sentrek.record.DeletePlaceDialog
import app.sentrek.record.FollowConfirmations
import app.sentrek.record.GpsStatus
import app.sentrek.ui.Confirmation
import app.sentrek.ui.ConfirmDialog
import app.sentrek.record.PlaceDialog
import app.sentrek.record.PlaceSheet
import app.sentrek.record.SavedTrack
import app.sentrek.record.sharePlace
import app.sentrek.record.TrackNameDialog
import app.sentrek.ui.BarItem
import app.sentrek.ui.BottomBar
import app.sentrek.ui.LocalTouch
import app.sentrek.ui.TouchTarget
import app.sentrek.ui.MapButton
import app.sentrek.ui.MapCompass
import app.sentrek.ui.MapCredit
import app.sentrek.ui.ScaleBar
import app.sentrek.ui.safeTopPadding
import app.sentrek.ui.SentrekIcons
import kotlinx.coroutines.delay
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

/** Turned less than this from north, the map snaps back to north when it stops. */
private const val SNAP_NORTH_DEGREES = 5.0

private const val NORTH_ANIMATION_MS = 300

/** Shorter than this, there is no way back to follow: the start is in sight. */
private const val BACK_TO_START_MIN_M = 50.0

/** Ground distance across one screen pixel at the centre of the map. */
private fun metersPerPixel(map: MapLibreMap): Double {
    val x = map.width / 2
    val y = map.height / 2
    if (x <= 0f || y <= 0f) return 0.0
    val a = map.projection.fromScreenLocation(PointF(x - SCALE_PROBE_PX / 2, y))
    val b = map.projection.fromScreenLocation(PointF(x + SCALE_PROBE_PX / 2, y))
    return a.distanceTo(b) / SCALE_PROBE_PX
}

private const val SCALE_PROBE_PX = 100f

/** How long without touches before buttons and bar fade out. */
private const val HIDE_CONTROLS_MS = 8_000L

/** How long "hold the lock to unlock" stays after a touch on a locked screen. */
private const val LOCK_HINT_MS = 2_500L

/** What a location permission request was for, to carry on once granted. */
private enum class AfterPermission { Follow, Record, FollowRoute }

@Composable
fun MapScreen(
    camera: CameraPosition,
    onCameraChange: (CameraPosition) -> Unit,
    onOpenMaps: () -> Unit,
    onOpenTracks: () -> Unit,
    onOpenPlaces: () -> Unit,
    onOpenSearch: () -> Unit,
    onOpenSettings: () -> Unit,
    focus: BoundingBox?,
    onFocusDone: () -> Unit,
    /** +1 / -1 zoom steps from the volume keys (glove mode). */
    zoomKeys: Flow<Int>,
    /** False while another screen covers the map (it stays loaded underneath). */
    visible: Boolean = true,
    viewModel: HomeViewModel = hiltViewModel(),
    planner: PlannerViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val overlays by viewModel.overlays.collectAsStateWithLifecycle()
    val showDifficulty by viewModel.showDifficulty.collectAsStateWithLifecycle()
    val rasters by viewModel.rasters.collectAsStateWithLifecycle()
    val recording by viewModel.recording.collectAsStateWithLifecycle()
    val shownTracks by viewModel.shownTracks.collectAsStateWithLifecycle()
    val followedRoute by viewModel.following.collectAsStateWithLifecycle()
    val followProfile by viewModel.followProfile.collectAsStateWithLifecycle()
    val followPlaces by viewModel.followPlaces.collectAsStateWithLifecycle()
    val places by viewModel.places.collectAsStateWithLifecycle()
    val plan by planner.state.collectAsStateWithLifecycle()
    val planStats by planner.stats.collectAsStateWithLifecycle()
    val planLegs by planner.legs.collectAsStateWithLifecycle()
    val planning by rememberUpdatedState(plan.active)
    // Points while a handle is dragged, before the drop commits them.
    var planPreview by remember { mutableStateOf<List<LatLon>?>(null) }
    var askPlanName by remember { mutableStateOf(false) }
    var askPlanDiscard by remember { mutableStateOf(false) }
    // An action waiting for "are you sure?".
    var confirm by remember { mutableStateOf<Confirmation?>(null) }
    val currentOnCameraChange by rememberUpdatedState(onCameraChange)
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var style by remember { mutableStateOf<Style?>(null) }
    var pressed by remember { mutableStateOf<MapFeatureInfo?>(null) }
    var hasLocation by remember { mutableStateOf(viewModel.hasLocationPermission()) }
    // Restarted once permission comes: the satellite count needs it.
    val gps by remember(hasLocation) { viewModel.gps() }.collectAsStateWithLifecycle(initialValue = null)
    val gpsTiming = rememberGpsLabelTiming(gps.takeIf { hasLocation })
    // A long press on the location button shows the position: while the finger stays on it,
    // and a few seconds after (time to tap it and copy the coordinates).
    val locationPress = remember { MutableInteractionSource() }
    val locationHeld by locationPress.collectIsPressedAsState()
    var showPosition by remember { mutableStateOf(false) }
    LaunchedEffect(showPosition, locationHeld) {
        if (!showPosition || locationHeld) return@LaunchedEffect
        delay(POSITION_LINGER_MS)
        showPosition = false
    }
    // How the camera follows the user, as MapLibre reports it. Taps on the location button go
    // through Off -> North up -> heading up -> Off, as in Trekarta; panning pauses following,
    // and the next tap resumes the mode it had ([resumeMode]).
    var followMode by remember { mutableStateOf(FollowMode.Off) }
    var resumeMode by remember { mutableStateOf(FollowMode.North) }
    // A mode to apply once the position marker exists (right after a permission grant it doesn't yet).
    var followRequest by remember { mutableStateOf<FollowMode?>(null) }
    val rotationLocked by viewModel.rotationLocked.collectAsStateWithLifecycle()
    // Buttons and bar fade out after a while without touches: more map, and nothing static to
    // burn into OLED screens. A touch brings them back. Not while planning: that needs them.
    var touches by remember { mutableIntStateOf(0) }
    var controlsHidden by remember { mutableStateOf(false) }
    val controlsAlpha by animateFloatAsState(if (controlsHidden) 0f else 1f, label = "controls")
    val autoHideSetting by viewModel.autoHideControls.collectAsStateWithLifecycle()
    val showScaleBar by viewModel.showScaleBar.collectAsStateWithLifecycle()
    val powerMode by viewModel.powerMode.collectAsStateWithLifecycle()
    val online by viewModel.online.collectAsStateWithLifecycle()
    val downloadedCells by viewModel.downloadedCells.collectAsStateWithLifecycle()
    val lastFixForSun by viewModel.lastFix.collectAsStateWithLifecycle()
    val autoHide = autoHideSetting && !plan.active
    // Touch lock: everything but the lock button ignores touches; the controls step aside.
    var locked by rememberSaveable { mutableStateOf(false) }
    val showLockButton by viewModel.showLockButton.collectAsStateWithLifecycle()
    var libraryOpen by rememberSaveable { mutableStateOf(false) }
    // Bumped by a touch while locked: shows how to unlock for a moment.
    var lockHint by remember { mutableIntStateOf(0) }
    var showLockHint by remember { mutableStateOf(false) }
    LaunchedEffect(lockHint) {
        if (lockHint == 0) return@LaunchedEffect
        showLockHint = true
        delay(LOCK_HINT_MS)
        showLockHint = false
    }
    LaunchedEffect(locked) { if (!locked) showLockHint = false }
    // Back would leave the map (or the app) under a wet thumb just the same.
    BackHandler(enabled = locked) { lockHint++ }
    LaunchedEffect(autoHide, touches, locked) {
        controlsHidden = locked
        if (locked || !autoHide) return@LaunchedEffect
        delay(HIDE_CONTROLS_MS)
        controlsHidden = true
    }
    var askStop by remember { mutableStateOf(false) }
    var askDiscard by remember { mutableStateOf(false) }
    var askName by remember { mutableStateOf(false) }
    var afterPermission by remember { mutableStateOf<AfterPermission?>(null) }
    var routeToFollow by remember { mutableStateOf<SavedTrack?>(null) }
    // A place being created (id "") or edited, and one about to be deleted.
    var placeDraft by remember { mutableStateOf<Place?>(null) }
    var deletingPlace by remember { mutableStateOf<Place?>(null) }
    val unnamedPlace = stringResource(RecordR.string.places_unnamed)
    val haptics = LocalHapticFeedback.current
    val pressRadiusPx = with(LocalDensity.current) { LocalTouch.current.pressRadius.toPx() }
    // "Right under the finger": what a fingertip covers, but never wider than the mode's reach.
    val exactPressPx = with(LocalDensity.current) { minOf(LocalTouch.current.pressRadius, 10.dp).toPx() }
    // Plan handles: the drawn dot plus the mode's reach, never less than a fingertip.
    val handleReach = maxOf(24.dp, LocalTouch.current.pressRadius + 12.dp)
    val handleReachPx = with(LocalDensity.current) { handleReach.toPx() }
    val touchSlopPx = with(LocalDensity.current) { 8.dp.toPx() }
    val currentPlan by rememberUpdatedState(plan)
    val currentLegs by rememberUpdatedState(planLegs)
    val planGestures = remember(handleReachPx) {
        PlanGestures(
            hitPx = handleReachPx,
            slopPx = touchSlopPx,
            onTap = { handle ->
                when (handle) {
                    is PlanHandle.Point -> planner.select(handle.index.takeIf { it != currentPlan.selected })
                    is PlanHandle.Mid -> currentLegs.getOrNull(handle.index)?.let { planner.insert(handle.index, it.middle) }
                }
            },
            onDrag = { handle, at ->
                planPreview = currentPlan.positions.toMutableList().apply {
                    when (handle) {
                        is PlanHandle.Point -> set(handle.index, at)
                        is PlanHandle.Mid -> add(handle.index + 1, at)
                    }
                }
            },
            onDrop = { handle, at ->
                planPreview = null
                if (at != null) {
                    when (handle) {
                        is PlanHandle.Point -> planner.move(handle.index, at)
                        is PlanHandle.Mid -> planner.insert(handle.index, at)
                    }
                }
            },
        )
    }
    // The touch that brings the controls back does nothing else (no pan, no feature sheet).
    var swallowing by remember { mutableStateOf(false) }
    val onMapTouch: (MotionEvent) -> Boolean = { event ->
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            // A touch on the map closes the library row, as it would a menu.
            libraryOpen = false
            swallowing = controlsHidden
        }
        val swallow = swallowing
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) swallowing = false
        swallow || planGestures.onTouch(event)
    }
    planGestures.map = map
    planGestures.points = if (plan.active) plan.positions else emptyList()
    planGestures.midpoints = if (plan.active) planLegs.map { it.middle } else emptyList()

    // The user may grant or revoke location in system settings while we're in the background.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { hasLocation = viewModel.hasLocationPermission() }

    val follow = { if (followMode == FollowMode.Off) followRequest = resumeMode }
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
            next == AfterPermission.FollowRoute -> routeToFollow?.let(viewModel::followTrack)
        }
        routeToFollow = null
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

    // The bottom bar is drawn over the map, so showing and hiding it never resizes the map.
    // Its last height keeps the buttons above it either way.
    var barHeightPx by remember { mutableIntStateOf(0) }
    val barHeight = with(LocalDensity.current) { barHeightPx.toDp() }

    // For the scale bar and the compass, kept up to date while the map moves.
    var metersPerPx by remember { mutableDoubleStateOf(0.0) }
    var bearing by remember { mutableDoubleStateOf(0.0) }
    // The area in the middle of the map, to tell whether it's downloaded.
    var centreCell by remember { mutableStateOf<RegionCell?>(null) }
    DisposableEffect(map) {
        val m = map ?: return@DisposableEffect onDispose {}
        fun updateCentre() {
            val target = m.cameraPosition.target ?: return
            centreCell = RegionGrid.cellAt(LatLon(target.latitude, target.longitude.coerceIn(-180.0, 180.0)))
        }
        val onMove = MapLibreMap.OnCameraMoveListener {
            metersPerPx = metersPerPixel(m)
            bearing = m.cameraPosition.bearing
        }
        val onIdle = MapLibreMap.OnCameraIdleListener {
            metersPerPx = metersPerPixel(m)
            bearing = m.cameraPosition.bearing
            updateCentre()
            // Left a few degrees off north: almost certainly meant to be north.
            val off = minOf(bearing, 360 - bearing)
            if (followMode != FollowMode.Heading && off > 0.0 && off < SNAP_NORTH_DEGREES) {
                m.animateCamera(CameraUpdateFactory.bearingTo(0.0), NORTH_ANIMATION_MS)
            }
        }
        metersPerPx = metersPerPixel(m)
        bearing = m.cameraPosition.bearing
        updateCentre()
        m.addOnCameraMoveListener(onMove)
        m.addOnCameraIdleListener(onIdle)
        onDispose {
            m.removeOnCameraMoveListener(onMove)
            m.removeOnCameraIdleListener(onIdle)
        }
    }

    // Every touch on the screen (map or buttons) restarts the hide timer; nothing is consumed.
    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.type == PointerEventType.Press) touches++
                    }
                }
            },
    ) {
        Box(Modifier.fillMaxSize()) {
            SentrekMap(
                style = MapStyle.Hiking,
                modifier = Modifier.fillMaxSize(),
                onMapReady = { m, s ->
                    m.cameraPosition = camera
                    // Our own compass (MapCompass) instead, with Trekarta's tap and long press.
                    m.uiSettings.isCompassEnabled = false
                    map = m
                    style = s
                },
                onTouch = onMapTouch,
            )

            Column(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .safeTopPadding()
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .padding(end = TouchTarget), // Keep clear of the compass.
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (plan.active) PlanPanel(planStats, plan.editing?.name, Modifier.fillMaxWidth())
                // Where the sun sets for the user: their position, or the map's before a fix.
                val sunPosition = lastFixForSun?.let { LatLon(it.latitude, it.longitude) }
                    ?: map?.cameraPosition?.target?.let { LatLon(it.latitude, it.longitude) }
                recording?.let {
                    // While following, the route's panel says it, against the time left.
                    RecordingStats(recording = it, modifier = Modifier.fillMaxWidth(), position = sunPosition.takeIf { followedRoute == null })
                }
                followedRoute?.let {
                    FollowPanel(
                        following = it,
                        showProfile = followProfile,
                        onToggleProfile = { viewModel.setFollowProfile(!followProfile) },
                        showPlaces = followPlaces,
                        onTogglePlaces = { viewModel.setFollowPlaces(!followPlaces) },
                        onOpen = {
                            pressed = MapFeatureInfo(name = null, kind = "track", position = LatLng(0.0, 0.0), osmId = null, trackId = it.trackId)
                        },
                        onStop = { confirm = FollowConfirmations.stop(context, it.name, viewModel::stopFollowing) },
                        modifier = Modifier.fillMaxWidth(),
                        position = sunPosition,
                    )
                }
                // Top left, under any panel. It stays when the other controls fade out.
                if (showScaleBar) ScaleBar(metersPerPx, night = LocalNightMap.current)
                // A reminder, not a block: the map still moves and answers taps, it just has
                // nothing to draw here until the area is downloaded or the network is back.
                if (!online && centreCell?.let { it !in downloadedCells } == true) {
                    MapNote(stringResource(R.string.offline_area), SentrekIcons.CloudOff)
                }
                // Never forgotten: they change how the whole app behaves.
                when (powerMode) {
                    PowerMode.Stamina -> MapNote(stringResource(R.string.stamina_on_map), SentrekIcons.BatteryAlert)
                    PowerMode.Saver -> MapNote(stringResource(R.string.saver_on_map), SentrekIcons.BatterySaver)
                    PowerMode.Normal -> Unit
                }
            }

            MapCompass(
                bearing = bearing,
                locked = rotationLocked,
                onClick = {
                    // Out of heading up too: the map would turn away again at the next fix.
                    if (followMode != FollowMode.Off) {
                        followRequest = FollowMode.North
                    } else {
                        map?.animateCamera(CameraUpdateFactory.bearingTo(0.0), NORTH_ANIMATION_MS)
                    }
                },
                onLongClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    val locked = !rotationLocked
                    viewModel.setRotationLocked(locked)
                    Toast.makeText(context, if (locked) R.string.rotation_locked else R.string.rotation_unlocked, Toast.LENGTH_SHORT).show()
                },
                contentDescription = stringResource(R.string.compass_north),
                longClickLabel = stringResource(if (rotationLocked) R.string.compass_unlock else R.string.compass_lock),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .safeTopPadding()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )

            // With the controls hidden, GPS trouble must still show.
            if (controlsHidden) {
                GpsIndicator(
                    status = gps.takeIf { hasLocation },
                    timing = gpsTiming,
                    onTurnOn = { openLocationSettings(context) },
                    problemsOnly = true,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 12.dp, bottom = barHeight + 24.dp)
                        .widthIn(max = 240.dp),
                )
            }

            // Map controls stay on the right edge, in the lower half: reachable with the thumb.
            if (controlsAlpha > 0f) Column(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .alpha(controlsAlpha)
                    .padding(end = 12.dp, bottom = barHeight + 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.End,
            ) {
                if (plan.active) {
                    MapButton(
                        icon = SentrekIcons.Tracks,
                        contentDescription = stringResource(PlannerR.string.plan_follow_trails),
                        onClick = planner::toggleTrails,
                        active = plan.followTrails,
                    )
                }
                // Bottom-aligned: a taller label grows upwards and the button stays under the finger.
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (showPosition && gps is GpsStatus.Fix) {
                        CoordinatesLabel(viewModel.lastFix, Modifier.widthIn(max = 300.dp))
                    } else {
                        GpsIndicator(
                            status = gps.takeIf { hasLocation },
                            timing = gpsTiming,
                            onTurnOn = { openLocationSettings(context) },
                            modifier = Modifier.widthIn(max = 240.dp),
                        )
                    }
                    MapButton(
                        icon = if (followMode == FollowMode.Heading) SentrekIcons.Heading else SentrekIcons.MyLocation,
                        contentDescription = stringResource(
                            when (followMode) {
                                FollowMode.Off -> R.string.my_location
                                FollowMode.North -> R.string.my_location_heading
                                FollowMode.Heading -> R.string.my_location_stop
                            },
                        ),
                        onClick = {
                            showPosition = false
                            when {
                                // Following a position that can't come is pointless: say why (the
                                // reminder itself opens the location settings).
                                hasLocation && gps == GpsStatus.Off -> gpsTiming.remindOff()
                                followMode == FollowMode.Off -> withLocation(AfterPermission.Follow) { follow() }
                                // With rotation locked there is no heading up: straight to off.
                                followMode == FollowMode.North && !rotationLocked -> followRequest = FollowMode.Heading
                                else -> {
                                    resumeMode = FollowMode.North
                                    followRequest = FollowMode.Off
                                    map?.animateCamera(CameraUpdateFactory.bearingTo(0.0), NORTH_ANIMATION_MS)
                                }
                            }
                        },
                        onLongClick = {
                            // Without a fix the GPS label already says why there is no position.
                            if (gps is GpsStatus.Fix) {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                showPosition = true
                            }
                        },
                        longClickLabel = stringResource(R.string.my_location_details),
                        interactionSource = locationPress,
                        active = followMode != FollowMode.Off,
                    )
                }
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

        // Bottom left, above the bar, for the first seconds. Clear of the buttons on the right:
        // the credit wraps instead.
        MapCredit(
            Modifier
                .align(Alignment.BottomStart)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Start))
                .padding(start = TouchTarget + 24.dp, end = TouchTarget + 24.dp, bottom = barHeight + 12.dp),
        )

        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .onSizeChanged { if (it.height > 0) barHeightPx = it.height },
        ) {
            if (plan.active) {
                BottomBar(
                    items = listOf(
                        BarItem(SentrekIcons.Undo, stringResource(PlannerR.string.plan_undo), planner::undo, enabled = plan.canUndo),
                        BarItem(SentrekIcons.Redo, stringResource(PlannerR.string.plan_redo), planner::redo, enabled = plan.canRedo),
                        BarItem(SentrekIcons.Delete, stringResource(PlannerR.string.plan_remove), planner::removePoint, enabled = plan.points.isNotEmpty()),
                        BarItem(SentrekIcons.Check, stringResource(PlannerR.string.plan_save), { askPlanName = true }, enabled = plan.points.size >= 2),
                        BarItem(SentrekIcons.Close, stringResource(PlannerR.string.plan_close), {
                            // Nothing to lose: an empty plan, or a saved route opened and left as it was.
                            val unchanged = plan.editing != null && !plan.canUndo
                            if (plan.points.isEmpty() || unchanged) planner.close() else askPlanDiscard = true
                        }),
                    ),
                )
            } else AnimatedVisibility(visible = !controlsHidden, enter = fadeIn(), exit = fadeOut()) { Column {
                // Library: what's kept on the phone, opened less often than the rest of the bar.
                if (libraryOpen) {
                    BottomBar(
                        items = listOf(
                            BarItem(SentrekIcons.Tracks, stringResource(R.string.bar_tracks), { libraryOpen = false; onOpenTracks() }),
                            BarItem(SentrekIcons.Place, stringResource(R.string.bar_places), { libraryOpen = false; onOpenPlaces() }),
                            BarItem(SentrekIcons.Layers, stringResource(R.string.bar_maps), { libraryOpen = false; onOpenMaps() }),
                        ),
                        edgeToEdge = false,
                    )
                }
                BottomBar(
                items = listOf(
                    BarItem(SentrekIcons.Library, stringResource(R.string.bar_library), { libraryOpen = !libraryOpen }, highlighted = libraryOpen),
                    BarItem(SentrekIcons.Plan, stringResource(R.string.bar_plan), { libraryOpen = false; planner.start() }),
                    if (recording?.isPaused == true) {
                        BarItem(SentrekIcons.Pause, stringResource(R.string.bar_paused), { askStop = true }, highlighted = true)
                    } else if (recording != null) {
                        BarItem(SentrekIcons.Stop, stringResource(R.string.bar_stop), { askStop = true }, highlighted = true)
                    } else {
                        BarItem(SentrekIcons.Record, stringResource(R.string.bar_record), {
                            withLocation(AfterPermission.Record) { startRecording() }
                        })
                    },
                    BarItem(SentrekIcons.Search, stringResource(R.string.search_open), { libraryOpen = false; onOpenSearch() }),
                    BarItem(Icons.Filled.Settings, stringResource(R.string.bar_settings), { libraryOpen = false; onOpenSettings() }),
                ),
            ) } }
        }

        if (locked) TouchShield(onTouch = { lockHint++ })
        // Bottom left, where the thumb rests, opposite the map buttons. Shown while locked
        // whatever the setting says: there must always be a way out.
        if (!plan.active && (locked || (showLockButton && controlsAlpha > 0f))) Row(
            Modifier
                .align(Alignment.BottomStart)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Start))
                .padding(start = 12.dp, end = TouchTarget + 24.dp, bottom = barHeight + 24.dp)
                .alpha(if (locked) 1f else controlsAlpha),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            LockButton(
                locked = locked,
                onLock = { locked = true },
                onUnlock = { locked = false },
                onShortPress = { lockHint++ },
            )
            if (showLockHint) MapNote(stringResource(R.string.lock_hint), SentrekIcons.Lock)
        }
    }

    DisposableEffect(map) {
        val m = map ?: return@DisposableEffect onDispose {}
        val onIdle = MapLibreMap.OnCameraIdleListener { currentOnCameraChange(m.cameraPosition) }
        m.addOnCameraIdleListener(onIdle)
        onDispose { m.removeOnCameraIdleListener(onIdle) }
    }

    DisposableEffect(map) {
        val m = map ?: return@DisposableEffect onDispose {}
        val onLongPress = MapLibreMap.OnMapLongClickListener { pressedAt ->
            // While planning, taps add points and long presses do nothing.
            if (planning) return@OnMapLongClickListener true
            val latLng = pressedAt.wrap()
            val info = MapFeatures.at(m, m.projection.toScreenLocation(pressedAt), latLng, pressRadiusPx, exactPressPx)
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            // Nothing there: offer to save the spot as a place.
            if (info == null) {
                placeDraft = Place("", unnamedPlace, PlaceKind.Star, LatLon(latLng.latitude, latLng.longitude))
            } else {
                pressed = info
            }
            true
        }
        m.addOnMapLongClickListener(onLongPress)
        val onClick = MapLibreMap.OnMapClickListener { at ->
            if (!planning) return@OnMapClickListener false
            val p = at.wrap()
            planner.add(LatLon(p.latitude, p.longitude))
            true
        }
        m.addOnMapClickListener(onClick)
        onDispose {
            m.removeOnMapLongClickListener(onLongPress)
            m.removeOnMapClickListener(onClick)
        }
    }

    // Position on the map while it's on screen; the GPS is off otherwise (unless recording or
    // following, at the tracking service's slower rate). Covered by another screen, the arrow
    // goes too, and with it the compass and the redraws.
    val markerPower = when (powerMode) {
        PowerMode.Normal -> MapLocation.Power.Normal
        PowerMode.Saver -> MapLocation.Power.Saver
        PowerMode.Stamina -> MapLocation.Power.Minimal
    }
    val gpsMs by viewModel.screenGpsMs.collectAsStateWithLifecycle()
    LaunchedEffect(map, style, hasLocation, visible, markerPower, gpsMs) {
        val m = map ?: return@LaunchedEffect
        val s = style ?: return@LaunchedEffect
        if (!hasLocation) return@LaunchedEffect
        if (!visible) return@LaunchedEffect MapLocation.hide(m)
        MapLocation.activate(context, m, s, markerPower)
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            viewModel.locations(gpsMs).collect { MapLocation.update(m, it) }
        }
    }

    LaunchedEffect(map, style, hasLocation, followRequest) {
        val m = map ?: return@LaunchedEffect
        val s = style ?: return@LaunchedEffect
        val mode = followRequest ?: return@LaunchedEffect
        if (!hasLocation) return@LaunchedEffect
        MapLocation.activate(context, m, s, markerPower)
        MapLocation.follow(m, mode)
        followRequest = null
    }

    DisposableEffect(map, style, hasLocation) {
        val m = map
        if (m == null || style == null || !hasLocation) return@DisposableEffect onDispose {}
        val stop = MapLocation.observeFollow(m) { mode ->
            // Stopped by a gesture, not by the button: the next tap picks up where it was.
            if (mode == FollowMode.Off && followMode != FollowMode.Off && followRequest != FollowMode.Off) {
                resumeMode = followMode
            }
            followMode = mode
        }
        onDispose { stop() }
    }

    // Locked: north up, no turning with two fingers, and no heading-up mode. Heading up turns
    // the map itself, so two fingers can't turn it there either.
    LaunchedEffect(map, rotationLocked, followMode) {
        val m = map ?: return@LaunchedEffect
        m.uiSettings.isRotateGesturesEnabled = !rotationLocked && followMode != FollowMode.Heading
        if (!rotationLocked) return@LaunchedEffect
        if (followMode == FollowMode.Heading) followRequest = FollowMode.North
        if (m.cameraPosition.bearing != 0.0) m.animateCamera(CameraUpdateFactory.bearingTo(0.0), NORTH_ANIMATION_MS)
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

    pressed?.let { info ->
        val track = info.trackId?.takeIf { info.kind == "track" }?.let(viewModel::track)
        val place = info.placeId?.let(viewModel::place)
        if (place != null) {
            val here = map?.let(MapLocation::lastLocation)
            PlaceSheet(
                place = place,
                distance = here?.let { Geo.distance(LatLon(it.latitude, it.longitude), place.position) },
                onEdit = { pressed = null; placeDraft = place },
                onShare = { sharePlace(context, place) },
                onDelete = { pressed = null; deletingPlace = place },
                onDismiss = { pressed = null },
            )
        } else if (track == null) {
            FeatureSheet(
                info,
                onSaveAsPlace = {
                    pressed = null
                    val p = info.position
                    placeDraft = Place(
                        id = "",
                        name = info.name ?: info.kind?.let(::humanize) ?: unnamedPlace,
                        kind = PlaceKind.guess(info.kind),
                        position = LatLon(p.latitude, p.longitude, info.tags["ele"]?.toDoubleOrNull()),
                    )
                },
                onDismiss = { pressed = null },
            )
        } else {
            TrackSheet(
                track = track,
                isFollowed = followedRoute?.trackId == track.id,
                onFollow = {
                    pressed = null
                    val start = {
                        routeToFollow = track
                        withLocation(AfterPermission.FollowRoute) { viewModel.followTrack(track); routeToFollow = null }
                    }
                    val current = followedRoute
                    if (current != null) confirm = FollowConfirmations.switch(context, current.name, track.name, start) else start()
                },
                onStopFollowing = {
                    pressed = null
                    confirm = FollowConfirmations.stop(context, track.name, viewModel::stopFollowing)
                },
                onReverse = { pressed = null; viewModel.reverseRoute() },
                onColor = { viewModel.setTrackColor(track, it) },
                onHide = {
                    pressed = null
                    if (followedRoute?.trackId == track.id) {
                        confirm = FollowConfirmations.hideFollowed(context, track.name) {
                            viewModel.stopFollowing()
                            viewModel.hideTrack(track.id)
                        }
                    } else {
                        viewModel.hideTrack(track.id)
                    }
                },
                onEdit = if (track.imported) {
                    {
                        pressed = null
                        planner.editRoute(track) { opened ->
                            if (!opened) Toast.makeText(context, PlannerR.string.plan_busy, Toast.LENGTH_LONG).show()
                        }
                    }
                } else {
                    null
                },
                onDismiss = { pressed = null },
                loadProfile = viewModel::trackProfile,
            )
        }
    }

    placeDraft?.let { draft ->
        val isNew = draft.id.isEmpty()
        PlaceDialog(
            title = stringResource(if (isNew) RecordR.string.places_new else RecordR.string.places_edit),
            initial = draft,
            onSave = {
                if (isNew) viewModel.addPlace(it) else viewModel.updatePlace(it)
                placeDraft = null
            },
            onDismiss = { placeDraft = null },
        )
    }
    deletingPlace?.let { place ->
        DeletePlaceDialog(place, onDelete = { viewModel.deletePlace(place); deletingPlace = null }, onDismiss = { deletingPlace = null })
    }

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
            onBackToStart = {
                askStop = false
                val name = context.getString(R.string.record_way_back, viewModel.defaultTrackName(current))
                viewModel.backToStart(name) { ok ->
                    Toast.makeText(context, if (ok) R.string.record_back_started else R.string.record_back_nothing, Toast.LENGTH_SHORT).show()
                }
            }.takeIf { current.distance >= BACK_TO_START_MIN_M },
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

    val contourTerrain by viewModel.contourTerrain.collectAsStateWithLifecycle()
    val elevationUnit = viewModel.units.collectAsStateWithLifecycle().value.elevation
    val nightMap = LocalNightMap.current
    LaunchedEffect(style, contourTerrain, elevationUnit) {
        style?.let { MapContours.sync(it, contourTerrain, elevationUnit, nightMap) }
    }
    LaunchedEffect(style, elevationUnit) {
        style?.let { MapHeights.apply(it, elevationUnit) }
    }
    LaunchedEffect(style, powerMode) {
        style?.let { MapContours.showRelief(it, powerMode != PowerMode.Stamina) }
    }

    LaunchedEffect(style, plan, planLegs, planPreview) {
        val s = style ?: return@LaunchedEffect
        if (!plan.active) {
            PlanLayers.clear(s)
            return@LaunchedEffect
        }
        // While dragging, legs that changed are straight until the finger lifts.
        val preview = planPreview
        if (preview != null) {
            PlanLayers.sync(s, preview, preview.zipWithNext { a, b -> planner.knownLeg(a, b, onTrails = true) }, plan.selected)
        } else {
            PlanLayers.sync(s, plan.positions, planLegs, plan.selected)
        }
    }

    // Paths to follow come from the map's own tiles, around what is on screen.
    LaunchedEffect(map, style) {
        val m = map ?: return@LaunchedEffect
        val s = style ?: return@LaunchedEffect
        planner.trails = object : TrailSource {
            override suspend fun lines(box: BoundingBox) = MapTrails.lines(s, box)

            // MapLibre's "pixels" here are density-independent: the same unit as dp.
            override fun snapRadiusM() =
                m.projection.getMetersPerPixelAtLatitude(m.cameraPosition.target?.latitude ?: 0.0) * handleReach.value
        }
    }

    if (askPlanName) {
        TrackNameDialog(
            title = stringResource(PlannerR.string.plan_save_title),
            confirmLabel = stringResource(PlannerR.string.plan_save),
            initial = planner.defaultName(),
            onConfirm = { name ->
                askPlanName = false
                planner.save(name) { saved ->
                    Toast.makeText(context, context.getString(PlannerR.string.plan_saved, saved.name), Toast.LENGTH_LONG).show()
                }
            },
            onDismiss = { askPlanName = false },
        )
    }
    confirm?.let { ConfirmDialog(it) { confirm = null } }

    if (askPlanDiscard) {
        DiscardPlanDialog(
            onDiscard = { askPlanDiscard = false; planner.discard() },
            onDismiss = { askPlanDiscard = false },
        )
    }

    LaunchedEffect(style, places) {
        style?.let { PlaceLayers.sync(context, it, places) }
    }

    LaunchedEffect(style, recording?.segments, shownTracks) {
        style?.let { TrackLayers.sync(it, recording?.segments.orEmpty(), shownTracks) }
    }
}
