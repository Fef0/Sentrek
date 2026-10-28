package app.sentrek.home

import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.animation.fadeOut
import androidx.compose.animation.fadeIn
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.draw.alpha
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.layout.statusBarsPadding
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
import androidx.compose.runtime.mutableIntStateOf
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
import app.sentrek.geo.BoundingBox
import app.sentrek.geo.Geo
import app.sentrek.geo.LatLon
import app.sentrek.geo.place.Place
import app.sentrek.geo.place.PlaceKind
import app.sentrek.map.MapFeatureInfo
import app.sentrek.map.MapFeatures
import app.sentrek.map.MapLocation
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

/** Space between the bottom bar and the map's attribution button, in pixels. */
private const val ATTRIBUTION_GAP_PX = 16

/** How long without touches before buttons and bar fade out. */
private const val HIDE_CONTROLS_MS = 8_000L

/** What a location permission request was for, to carry on once granted. */
private enum class AfterPermission { Follow, Record, FollowRoute }

@Composable
fun MapScreen(
    camera: CameraPosition,
    onCameraChange: (CameraPosition) -> Unit,
    onOpenMaps: () -> Unit,
    onOpenTracks: () -> Unit,
    onOpenPlaces: () -> Unit,
    onOpenSettings: () -> Unit,
    focus: BoundingBox?,
    onFocusDone: () -> Unit,
    /** +1 / -1 zoom steps from the volume keys (glove mode). */
    zoomKeys: Flow<Int>,
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
    // Counts taps on the location button with a fix; each shows the coordinates for a while.
    var coordinatesTaps by remember { mutableIntStateOf(0) }
    LaunchedEffect(coordinatesTaps) {
        if (coordinatesTaps == 0) return@LaunchedEffect
        delay(COORDINATES_MS)
        coordinatesTaps = 0
    }
    var following by remember { mutableStateOf(false) }
    // Buttons and bar fade out after a while without touches: more map, and nothing static to
    // burn into OLED screens. A touch brings them back. Not while planning: that needs them.
    var touches by remember { mutableIntStateOf(0) }
    var controlsHidden by remember { mutableStateOf(false) }
    val controlsAlpha by animateFloatAsState(if (controlsHidden) 0f else 1f, label = "controls")
    val autoHideSetting by viewModel.autoHideControls.collectAsStateWithLifecycle()
    val autoHide = autoHideSetting && !plan.active
    LaunchedEffect(autoHide, touches) {
        controlsHidden = false
        if (!autoHide) return@LaunchedEffect
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
    LaunchedEffect(map, barHeightPx) {
        val ui = map?.uiSettings ?: return@LaunchedEffect
        ui.setAttributionMargins(ui.attributionMarginLeft, ui.attributionMarginTop, ui.attributionMarginRight, barHeightPx + ATTRIBUTION_GAP_PX)
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
                    map = m
                    style = s
                },
                onTouch = onMapTouch,
            )

            Column(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .padding(end = TouchTarget), // Keep clear of the compass.
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (plan.active) PlanPanel(planStats, plan.editing?.name, Modifier.fillMaxWidth())
                recording?.let { RecordingStats(recording = it, modifier = Modifier.fillMaxWidth()) }
                followedRoute?.let {
                    FollowPanel(
                        following = it,
                        showProfile = followProfile,
                        onToggleProfile = { viewModel.setFollowProfile(!followProfile) },
                        onOpen = {
                            pressed = MapFeatureInfo(name = null, kind = "track", position = LatLng(0.0, 0.0), osmId = null, trackId = it.trackId)
                        },
                        onStop = { confirm = FollowConfirmations.stop(context, it.name, viewModel::stopFollowing) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

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
                } else {
                    MapButton(
                        icon = SentrekIcons.Plan,
                        contentDescription = stringResource(PlannerR.string.plan_start),
                        onClick = planner::start,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (coordinatesTaps > 0 && gps is GpsStatus.Fix) {
                        CoordinatesLabel(viewModel.lastFix, Modifier.widthIn(max = 260.dp))
                    } else {
                        GpsIndicator(
                            status = gps.takeIf { hasLocation },
                            timing = gpsTiming,
                            onTurnOn = { openLocationSettings(context) },
                            modifier = Modifier.widthIn(max = 240.dp),
                        )
                    }
                    MapButton(
                        icon = SentrekIcons.MyLocation,
                        contentDescription = stringResource(R.string.my_location),
                        onClick = {
                            // Following a position that can't come is pointless: help switch the GPS on.
                            if (hasLocation && gps == GpsStatus.Off) {
                                openLocationSettings(context)
                            } else {
                                if (gps is GpsStatus.Fix) coordinatesTaps++
                                withLocation(AfterPermission.Follow) { follow() }
                            }
                        },
                        active = following,
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
            } else AnimatedVisibility(visible = !controlsHidden, enter = fadeIn(), exit = fadeOut()) { BottomBar(
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
                    BarItem(SentrekIcons.Place, stringResource(R.string.bar_places), onOpenPlaces),
                    BarItem(Icons.Filled.Settings, stringResource(R.string.bar_settings), onOpenSettings),
                ),
            ) }
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
