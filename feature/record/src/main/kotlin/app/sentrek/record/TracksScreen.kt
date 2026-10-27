package app.sentrek.record

import android.Manifest
import android.content.Context
import android.content.Intent
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
import androidx.compose.foundation.layout.statusBarsPadding
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.sentrek.feature.record.R
import app.sentrek.geo.BoundingBox
import app.sentrek.geo.route.HikingTime
import app.sentrek.ui.ElevationProfile
import app.sentrek.ui.Format
import app.sentrek.ui.TouchTarget
import app.sentrek.ui.MapButton
import app.sentrek.ui.SentrekIcons

/** Saved and imported tracks: follow, show on the map, share, save as GPX, rename, delete; import files. */
@Composable
fun TracksScreen(
    onBack: () -> Unit,
    onShowOnMap: (BoundingBox?) -> Unit,
    viewModel: TracksViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val tracks by viewModel.tracks.collectAsStateWithLifecycle()
    val shown by viewModel.shown.collectAsStateWithLifecycle()
    val following by viewModel.following.collectAsStateWithLifecycle()
    var exporting by remember { mutableStateOf<SavedTrack?>(null) }
    var deleting by remember { mutableStateOf<SavedTrack?>(null) }
    var renaming by remember { mutableStateOf<SavedTrack?>(null) }
    var toFollow by remember { mutableStateOf<SavedTrack?>(null) }
    var importing by remember { mutableStateOf(false) }
    var coloring by remember { mutableStateOf<SavedTrack?>(null) }

    val saveAs = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(GPX_MIME)) { uri ->
        val track = exporting ?: return@rememberLauncherForActivityResult
        exporting = null
        if (uri != null) {
            viewModel.export(track, uri) { ok ->
                Toast.makeText(context, if (ok) R.string.tracks_saved else R.string.tracks_save_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }
    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        importing = true
        viewModel.import(uris) { result ->
            importing = false
            showImportResult(context, result)
            result.bounds?.let(onShowOnMap)
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
                modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                MapButton(SentrekIcons.Back, stringResource(R.string.tracks_back), onBack)
                Text(
                    stringResource(R.string.tracks_title),
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.weight(1f),
                )
                OutlinedButton(
                    onClick = { pickFiles.launch(arrayOf("*/*")) },
                    enabled = !importing,
                    modifier = Modifier.heightIn(min = TouchTarget),
                ) { Text(stringResource(if (importing) R.string.tracks_importing else R.string.tracks_import)) }
            }
        }

        if (tracks.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                Text(
                    stringResource(R.string.tracks_empty),
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
                items(tracks, key = { it.id }) { track ->
                    TrackCard(
                        track = track,
                        isShown = track.id in shown,
                        isFollowed = track.id == following,
                        onFollow = {
                            when {
                                track.id == following -> viewModel.stopFollowing()
                                hasLocation(context) -> startFollowing(track)
                                else -> {
                                    toFollow = track
                                    locationPermission.launch(
                                        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                                    )
                                }
                            }
                        },
                        onToggleMap = {
                            if (track.id in shown) viewModel.hide(track) else viewModel.show(track, onShowOnMap)
                        },
                        onShare = {
                            viewModel.shareCopy(track) { file ->
                                val uri = FileProvider.getUriForFile(context, "${context.packageName}.tracks", file)
                                val send = Intent(Intent.ACTION_SEND)
                                    .setType(GPX_MIME)
                                    .putExtra(Intent.EXTRA_STREAM, uri)
                                    .putExtra(Intent.EXTRA_SUBJECT, track.name)
                                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                context.startActivity(Intent.createChooser(send, track.name))
                            }
                        },
                        onSave = {
                            exporting = track
                            saveAs.launch(TrackRepository.fileName(track))
                        },
                        onDelete = { deleting = track },
                        onRename = { renaming = track },
                        onColor = { coloring = track },
                        loadProfile = viewModel::profile,
                    )
                }
                item { Box(Modifier.navigationBarsPadding()) }
            }
        }
    }

    coloring?.let { track ->
        TrackColorDialog(
            current = track.color,
            onPick = { viewModel.setColor(track, it); coloring = null },
            onDismiss = { coloring = null },
        )
    }

    renaming?.let { track ->
        TrackNameDialog(
            title = stringResource(R.string.tracks_rename),
            confirmLabel = stringResource(R.string.tracks_rename_confirm),
            initial = track.name,
            onConfirm = { viewModel.rename(track, it); renaming = null },
            onDismiss = { renaming = null },
        )
    }

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
    onDelete: () -> Unit,
    onRename: () -> Unit,
    onColor: () -> Unit,
    loadProfile: suspend (SavedTrack) -> List<Pair<Double, Double>>,
) {
    // Keyed on the climb too: it changes when heights are added to an imported track.
    val profile by produceState(emptyList<Pair<Double, Double>>(), track.id, track.ascent) { value = loadProfile(track) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = TouchTarget)
                    .clickable(onClickLabel = stringResource(R.string.tracks_rename), onClick = onRename),
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
                Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.tracks_rename))
            }
            // Planned routes have no times: show the signpost estimate instead.
            val time = if (track.duration > 0) {
                Format.duration(track.duration)
            } else {
                Format.estimate(HikingTime.millis(track.distance, track.ascent, track.descent))
            }
            Text(
                stringResource(
                    R.string.tracks_stats,
                    Format.distance(track.distance),
                    time,
                    Format.meters(track.ascent),
                    Format.meters(track.descent),
                ),
                style = MaterialTheme.typography.bodyLarge,
            )
            ElevationProfile(profile, Modifier.fillMaxWidth().height(56.dp))
            val buttonModifier = Modifier.weight(1f).heightIn(min = TouchTarget)
            val padding = PaddingValues(horizontal = 4.dp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (isFollowed) {
                    Button(onClick = onFollow, modifier = buttonModifier, contentPadding = padding) {
                        Text(stringResource(R.string.tracks_stop_following))
                    }
                } else {
                    Button(onClick = onFollow, modifier = buttonModifier, contentPadding = padding) {
                        Text(stringResource(R.string.tracks_follow))
                    }
                }
                if (isShown) {
                    OutlinedButton(onClick = onToggleMap, modifier = buttonModifier, contentPadding = padding) {
                        Text(stringResource(R.string.tracks_hide))
                    }
                } else {
                    OutlinedButton(onClick = onToggleMap, modifier = buttonModifier, contentPadding = padding) {
                        Text(stringResource(R.string.tracks_show))
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onShare, modifier = buttonModifier, contentPadding = padding) {
                    Text(stringResource(R.string.tracks_share))
                }
                OutlinedButton(onClick = onSave, modifier = buttonModifier, contentPadding = padding) {
                    Text(stringResource(R.string.tracks_save))
                }
                OutlinedButton(onClick = onDelete, modifier = buttonModifier, contentPadding = padding) {
                    Text(stringResource(R.string.tracks_delete), color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

private const val GPX_MIME = "application/gpx+xml"
