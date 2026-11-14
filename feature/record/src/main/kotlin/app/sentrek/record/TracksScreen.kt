package app.sentrek.record

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.foundation.layout.size
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.sentrek.feature.record.R
import app.sentrek.geo.BoundingBox
import app.sentrek.geo.route.HikingTime
import app.sentrek.geo.track.PersonalPace
import app.sentrek.ui.Confirmation
import app.sentrek.ui.ConfirmDialog
import app.sentrek.ui.ElevationProfile
import app.sentrek.ui.SectionList
import app.sentrek.ui.sectionFlags
import app.sentrek.geo.plan.SectionTotals
import app.sentrek.ui.Format
import app.sentrek.ui.TouchTarget
import app.sentrek.ui.MapButton
import app.sentrek.ui.SentrekIcons
import app.sentrek.ui.safeTopPadding

/**
 * The user's [walks] (recorded here, with their stats) or their routes (planned or imported,
 * to follow): show on the map, follow, share, save as a file, name and notes, delete. Routes
 * also import files, and a walk can be copied to them; a route with times can be counted as
 * a walk.
 */
@Composable
fun TracksScreen(
    walks: Boolean,
    onBack: () -> Unit,
    onShowOnMap: (BoundingBox?) -> Unit,
    /** Opens a planned or imported route in the planner. */
    onEditRoute: (SavedTrack) -> Unit,
    viewModel: TracksViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val all by viewModel.tracks.collectAsStateWithLifecycle()
    val tracks = remember(all, walks) { all.filter { it.imported != walks } }
    // The user's pace from their walks: route times at it, once there are enough.
    val pace = remember(all) { PersonalPace.factor(all.filter { !it.imported }.trips()) }
    // Imported tracks with times, perhaps the user's own hikes: asked where they go.
    var askWalks by remember { mutableStateOf<List<SavedTrack>>(emptyList()) }
    val shown by viewModel.shown.collectAsStateWithLifecycle()
    val following by viewModel.following.collectAsStateWithLifecycle()
    val statsYear by viewModel.statsYear.collectAsStateWithLifecycle()
    val statsOpen by viewModel.statsOpen.collectAsStateWithLifecycle()
    val notesExpanded by viewModel.notesExpanded.collectAsStateWithLifecycle()
    var exporting by remember { mutableStateOf<SavedTrack?>(null) }
    var choosingFormat by remember { mutableStateOf<SavedTrack?>(null) }
    var confirm by remember { mutableStateOf<Confirmation?>(null) }
    var deleting by remember { mutableStateOf<SavedTrack?>(null) }
    val sharing = rememberSharing()
    var renaming by remember { mutableStateOf<SavedTrack?>(null) }
    var toFollow by remember { mutableStateOf<SavedTrack?>(null) }
    var importing by remember { mutableStateOf(false) }
    var coloring by remember { mutableStateOf<SavedTrack?>(null) }

    val exportTo = { uri: android.net.Uri?, kml: Boolean ->
        val track = exporting
        exporting = null
        if (track != null && uri != null) {
            viewModel.export(track, uri, kml) { ok ->
                Toast.makeText(context, if (ok) R.string.tracks_saved else R.string.tracks_save_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }
    val saveGpx = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(GPX_MIME)) { exportTo(it, false) }
    val saveKml = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(KML_MIME)) { exportTo(it, true) }
    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        importing = true
        viewModel.import(uris) { result ->
            importing = false
            showImportResult(context, result)
            val timed = when (result) {
                is ImportResult.Imported -> listOf(result.track)
                is ImportResult.ImportedMany -> result.tracks
                else -> emptyList()
            }.filter { it.duration > 0 }
            // Asked here, before the map: once on the map the question would be missed.
            if (timed.isNotEmpty()) askWalks = timed else result.bounds?.let(onShowOnMap)
        }
    }
    val startFollowing = { track: SavedTrack -> viewModel.follow(track, onShowOnMap) }
    val locationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val track = toFollow
        toFollow = null
        if (track != null && hasLocation(context)) startFollowing(track)
    }

    Column(Modifier.fillMaxSize()) {
        Surface(shadowElevation = 4.dp) {
            Row(
                modifier = Modifier.fillMaxWidth().safeTopPadding().padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                MapButton(SentrekIcons.Back, stringResource(R.string.tracks_back), onBack)
                Text(
                    stringResource(if (walks) R.string.walks_title else R.string.routes_title),
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.weight(1f),
                )
                // Files are routes (or, asked, the user's own walks): imported from Routes.
                if (!walks) {
                    OutlinedButton(
                        onClick = { pickFiles.launch(arrayOf("*/*")) },
                        enabled = !importing,
                        modifier = Modifier.heightIn(min = TouchTarget),
                    ) { Text(stringResource(if (importing) R.string.tracks_importing else R.string.tracks_import)) }
                }
            }
        }

        if (tracks.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                Text(
                    stringResource(if (walks) R.string.walks_empty else R.string.routes_empty),
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (walks) {
                    item(key = "totals") {
                        TripTotalsCard(
                            walks = tracks,
                            year = statsYear,
                            onYear = viewModel::setStatsYear,
                            open = statsOpen,
                            onOpen = viewModel::setStatsOpen,
                        )
                    }
                }
                items(tracks, key = { it.id }) { track ->
                    TrackCard(
                        track = track,
                        isShown = track.id in shown,
                        isFollowed = track.id == following,
                        onFollow = {
                            when {
                                track.id == following ->
                                    confirm = FollowConfirmations.stop(context, track.name, viewModel::stopFollowing)
                                else -> {
                                    val start = {
                                        if (hasLocation(context)) {
                                            startFollowing(track)
                                        } else {
                                            toFollow = track
                                            locationPermission.launch(
                                                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                                            )
                                        }
                                    }
                                    val current = all.firstOrNull { it.id == following }
                                    if (current != null) confirm = FollowConfirmations.switch(context, current.name, track.name, start) else start()
                                }
                            }
                        },
                        onToggleMap = {
                            when {
                                track.id !in shown -> viewModel.show(track, onShowOnMap)
                                track.id == following -> confirm = FollowConfirmations.hideFollowed(context, track.name) {
                                    viewModel.stopFollowing()
                                    viewModel.hide(track)
                                }
                                else -> viewModel.hide(track)
                            }
                        },
                        onShare = { sharing.share(track) },
                        onSave = { choosingFormat = track },
                        onDuplicate = {
                            viewModel.saveCopy(track, context.getString(R.string.tracks_copy_name, track.name)) { copy ->
                                val text = copy?.let { context.getString(R.string.tracks_saved_as, it.name) }
                                    ?: context.getString(R.string.tracks_copy_failed)
                                Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
                            }
                        },
                        onReverse = {
                            viewModel.saveReversed(track, context.getString(R.string.tracks_reversed_name, track.name)) { copy ->
                                val text = copy?.let { context.getString(R.string.tracks_saved_as, it.name) }
                                    ?: context.getString(R.string.tracks_reversed_failed)
                                Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
                            }
                        },
                        onEdit = if (track.imported) ({ onEditRoute(track) }) else null,
                        walk = walks,
                        pace = pace,
                        onCopyAsRoute = {
                            viewModel.copyAsRoute(track, track.name) { copy ->
                                val text = copy?.let { context.getString(R.string.walks_copied_to_routes, it.name) }
                                    ?: context.getString(R.string.tracks_copy_failed)
                                Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
                            }
                        },
                        // Only with times: a line without them wasn't walked.
                        onCountAsWalk = if (track.duration > 0) ({
                            viewModel.setWalk(track, true)
                            Toast.makeText(context, context.getString(R.string.routes_moved_to_walks, track.name), Toast.LENGTH_SHORT).show()
                        }) else null,
                        onDelete = { deleting = track },
                        onRename = { renaming = track },
                        onColor = { coloring = track },
                        loadProfile = viewModel::profile,
                        loadSections = viewModel::sections,
                        notesExpanded = notesExpanded,
                        onExpandNotes = viewModel::setNotesExpanded,
                    )
                }
                item { Box(Modifier.navigationBarsPadding()) }
            }
        }
    }

    confirm?.let { ConfirmDialog(it) { confirm = null } }

    if (askWalks.isNotEmpty()) {
        val asked = askWalks
        val done = { askWalks = emptyList() }
        AlertDialog(
            onDismissRequest = done,
            title = { Text(pluralStringResource(R.plurals.import_walks_title, asked.size, asked.size)) },
            text = { Text(stringResource(R.string.import_walks_text)) },
            confirmButton = {
                Button(onClick = { viewModel.setWalks(asked); done() }, modifier = Modifier.heightIn(min = TouchTarget)) {
                    Text(stringResource(R.string.import_walks_yes))
                }
            },
            dismissButton = {
                TextButton(onClick = done, modifier = Modifier.heightIn(min = TouchTarget)) {
                    Text(stringResource(R.string.import_walks_no))
                }
            },
        )
    }

    choosingFormat?.let { track ->
        AlertDialog(
            onDismissRequest = { choosingFormat = null },
            title = { Text(stringResource(R.string.tracks_save_as)) },
            text = { Text(stringResource(R.string.tracks_save_as_text)) },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            choosingFormat = null
                            exporting = track
                            saveKml.launch(TrackRepository.fileName(track, "kml"))
                        },
                        modifier = Modifier.heightIn(min = TouchTarget),
                    ) { Text(stringResource(R.string.tracks_format_kml)) }
                    Button(
                        onClick = {
                            choosingFormat = null
                            exporting = track
                            saveGpx.launch(TrackRepository.fileName(track))
                        },
                        modifier = Modifier.heightIn(min = TouchTarget),
                    ) { Text(stringResource(R.string.tracks_format_gpx)) }
                }
            },
            dismissButton = {
                TextButton(onClick = { choosingFormat = null }, modifier = Modifier.heightIn(min = TouchTarget)) {
                    Text(stringResource(R.string.tracks_cancel))
                }
            },
        )
    }

    coloring?.let { track ->
        TrackColorDialog(
            current = track.color,
            onPick = { viewModel.setColor(track, it); coloring = null },
            onDismiss = { coloring = null },
        )
    }

    renaming?.let { track ->
        TrackDetailsDialog(
            title = stringResource(R.string.tracks_details),
            confirmLabel = stringResource(R.string.places_save),
            initialName = track.name,
            initialNote = track.note,
            initialPhotos = track.photos,
            onConfirm = { name, note, photos -> viewModel.edit(track, name, note, photos); renaming = null },
            onDismiss = { renaming = null },
        )
    }

    ShareDialog(sharing)
    deleting?.let { track ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.tracks_delete_title)) },
            text = { Text(track.name) },
            confirmButton = {
                Button(
                    onClick = { viewModel.delete(track); deleting = null },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.heightIn(min = TouchTarget),
                ) { Text(stringResource(R.string.tracks_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }, modifier = Modifier.heightIn(min = TouchTarget)) {
                    Text(stringResource(R.string.tracks_cancel))
                }
            },
        )
    }
}

/** Toast for an import from this screen or from another app ("Open with", "Share"). */
fun showImportResult(context: Context, result: ImportResult) {
    val text = when (result) {
        is ImportResult.Imported -> context.getString(R.string.tracks_imported, result.track.name)
        is ImportResult.ImportedPlaces -> context.resources.getQuantityString(R.plurals.places_imported, result.count, result.count)
        is ImportResult.ImportedMany -> {
            val res = context.resources
            val places = result.places
            val main = if (result.tracks.isEmpty()) {
                res.getQuantityString(R.plurals.places_imported, places, places)
            } else {
                res.getQuantityString(R.plurals.tracks_imported_many, result.tracks.size, result.tracks.size) +
                    if (places > 0) res.getQuantityString(R.plurals.places_imported_also, places, places) else ""
            }
            main + if (result.failed > 0) res.getQuantityString(R.plurals.tracks_import_failed_many, result.failed, result.failed) else ""
        }
        ImportResult.Empty -> context.getString(R.string.tracks_import_empty)
        ImportResult.UnknownFormat -> context.getString(R.string.tracks_import_unknown)
        ImportResult.Failed -> context.getString(R.string.tracks_import_failed)
    }
    Toast.makeText(context, text, Toast.LENGTH_LONG).show()
}

private fun hasLocation(context: Context) =
    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

@Composable
private fun TrackCard(
    track: SavedTrack,
    isShown: Boolean,
    isFollowed: Boolean,
    onFollow: () -> Unit,
    onToggleMap: () -> Unit,
    onShare: () -> Unit,
    onSave: () -> Unit,
    /** Saves a copy as it is, to change it and keep the original. */
    onDuplicate: () -> Unit,
    /** Saves a copy walked the other way. */
    onReverse: () -> Unit,
    onDelete: () -> Unit,
    onRename: () -> Unit,
    onColor: () -> Unit,
    /** Null for recorded tracks, which can't be edited. */
    onEdit: (() -> Unit)?,
    /** A walk of the user's (its stats first), or a route (following it first). */
    walk: Boolean,
    /** The user's pace against signpost times, for a route's time; null until known. */
    pace: Double?,
    onCopyAsRoute: () -> Unit,
    /** Null when it can't be a walk (no times). */
    onCountAsWalk: (() -> Unit)?,
    loadProfile: suspend (SavedTrack) -> List<Pair<Double, Double>>,
    /** Each section's totals, for a route cut into sections in the planner. */
    loadSections: suspend (SavedTrack) -> List<SectionTotals>,
    notesExpanded: Boolean,
    onExpandNotes: (Boolean) -> Unit,
) {
    // Keyed on the climb too: it changes when heights are added to an imported track.
    val profile by produceState(emptyList<Pair<Double, Double>>(), track.id, track.ascent) { value = loadProfile(track) }
    val sections by produceState(emptyList<SectionTotals>(), track) { value = loadSections(track) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = TouchTarget)
                    .clickable(onClickLabel = stringResource(R.string.tracks_details), onClick = onRename),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ColorSwatch(track.color, selected = false, size = TouchTarget * 0.6f, onClick = onColor)
                Text(
                    track.name,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.tracks_details))
            }
            // A walk: how long it moved. A route: the signpost time (times it may have are
            // someone else's walk, not a promise for this one).
            val signpost = HikingTime.millis(track.distance, track.ascent, track.descent)
            val time = if (walk) Format.duration(track.duration) else Format.estimate(signpost)
            Text(
                stringResource(
                    R.string.tracks_stats,
                    Format.distance(track.distance),
                    time,
                    Format.elevation(track.ascent),
                    Format.elevation(track.descent),
                ),
                style = MaterialTheme.typography.bodyLarge,
            )
            val top = profile.maxOfOrNull { it.second }
            val details = if (walk) walkDetails(track, top) else routeDetails(signpost, pace, top)
            if (details.isNotEmpty()) {
                Text(details.joinToString(" · "), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            ElevationProfile(profile, Modifier.fillMaxWidth().height(56.dp), flags = sectionFlags(sections, profile))
            if (sections.isNotEmpty()) SectionList(sections)
            TrackNote(track.note, track.photos, notesExpanded, onExpandNotes, photoSize = TouchTarget * 1.2f)
            val buttonModifier = Modifier.weight(1f).heightIn(min = TouchTarget)
            val padding = PaddingValues(horizontal = 4.dp)
            val followLabel = if (isFollowed) R.string.tracks_stop_following else R.string.tracks_follow
            val mapLabel = if (isShown) R.string.tracks_hide else R.string.tracks_show
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (walk) {
                    // A walk is looked back on: the map first. Following it again is in the menu,
                    // unless it's being followed now, when stopping must be at hand.
                    Button(onClick = onToggleMap, modifier = buttonModifier, contentPadding = padding) {
                        Text(stringResource(mapLabel), textAlign = TextAlign.Center)
                    }
                    if (isFollowed) {
                        OutlinedButton(onClick = onFollow, modifier = buttonModifier, contentPadding = padding) {
                            Text(stringResource(followLabel))
                        }
                    }
                } else {
                    Button(onClick = onFollow, modifier = buttonModifier, contentPadding = padding) {
                        Text(stringResource(followLabel))
                    }
                    OutlinedButton(onClick = onToggleMap, modifier = buttonModifier, contentPadding = padding) {
                        Text(stringResource(mapLabel), textAlign = TextAlign.Center)
                    }
                }
                // The rest, used now and then, behind one button: the card stays short.
                Box {
                    var menu by remember { mutableStateOf(false) }
                    OutlinedButton(
                        onClick = { menu = true },
                        modifier = Modifier.size(TouchTarget),
                        contentPadding = PaddingValues(0.dp),
                    ) {
                        Icon(SentrekIcons.More, contentDescription = stringResource(R.string.tracks_more))
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        @Composable
                        fun item(label: Int, destructive: Boolean = false, action: () -> Unit) = DropdownMenuItem(
                            text = {
                                Text(
                                    stringResource(label),
                                    style = MaterialTheme.typography.titleMedium,
                                    color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                                )
                            },
                            onClick = { menu = false; action() },
                            modifier = Modifier.heightIn(min = TouchTarget),
                        )
                        if (walk) {
                            if (!isFollowed) item(R.string.tracks_follow, action = onFollow)
                            item(R.string.walks_copy_to_routes, action = onCopyAsRoute)
                        } else {
                            onEdit?.let { item(R.string.tracks_edit, action = it) }
                            item(R.string.tracks_reverse, action = onReverse)
                            item(R.string.tracks_duplicate, action = onDuplicate)
                            onCountAsWalk?.let { item(R.string.routes_count_as_walk, action = it) }
                        }
                        item(R.string.tracks_share, action = onShare)
                        item(R.string.tracks_save, action = onSave)
                        item(R.string.tracks_delete, destructive = true, action = onDelete)
                    }
                }
            }
        }
    }
}

/**
 * A walk's own numbers: speed while moving, climb per hour, time stopped, highest point.
 * Each only when it means something (a flat walk has no climb rate worth saying).
 */
@Composable
private fun walkDetails(track: SavedTrack, top: Double?): List<String> {
    val parts = ArrayList<String>()
    val hours = track.duration / 3_600_000.0
    // A few seconds' walk would give a speed, but a meaningless one.
    if (track.duration >= MIN_SPEED_MS && track.distance >= MIN_SPEED_M) {
        parts += stringResource(R.string.walk_speed, Format.speed(track.distance / (track.duration / 1000.0)))
    }
    if (hours >= MIN_RATE_HOURS && track.ascent >= MIN_RATE_CLIMB_M) {
        parts += stringResource(R.string.walk_climb_rate, Format.elevation(track.ascent / hours))
    }
    val stopped = (track.endTime - track.startTime) - track.duration
    if (stopped >= MIN_STOPPED_MS) parts += stringResource(R.string.walk_stopped, Format.duration(stopped))
    top?.let { parts += stringResource(R.string.track_top, Format.elevation(it)) }
    return parts
}

/** A route's: its time at the user's pace (once known), and its highest point. */
@Composable
private fun routeDetails(signpost: Long, pace: Double?, top: Double?): List<String> {
    val parts = ArrayList<String>()
    pace?.let { parts += stringResource(R.string.route_your_pace, Format.estimate((signpost * it).toLong())) }
    top?.let { parts += stringResource(R.string.track_top, Format.elevation(it)) }
    return parts
}

/** A climb rate from a short walk or a small climb would be noise. */
private const val MIN_RATE_HOURS = 0.5
private const val MIN_RATE_CLIMB_M = 100.0

/** Below these, a walk's speed is noise. */
private const val MIN_SPEED_MS = 5 * 60_000L
private const val MIN_SPEED_M = 500.0

/** Stops shorter than this aren't worth a word. */
private const val MIN_STOPPED_MS = 5 * 60_000L

private const val GPX_MIME = "application/gpx+xml"
private const val KML_MIME = "application/vnd.google-earth.kml+xml"
