package app.sentrek.record

import android.content.Intent
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.sentrek.feature.record.R
import app.sentrek.geo.BoundingBox
import app.sentrek.ui.Format
import app.sentrek.ui.TouchTarget
import app.sentrek.ui.MapButton
import app.sentrek.ui.SentrekIcons

/** Saved tracks: show on the map, share, save as GPX, delete. */
@Composable
fun TracksScreen(
    onBack: () -> Unit,
    onShowOnMap: (BoundingBox) -> Unit,
    viewModel: TracksViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val tracks by viewModel.tracks.collectAsStateWithLifecycle()
    val shown by viewModel.shown.collectAsStateWithLifecycle()
    var exporting by remember { mutableStateOf<SavedTrack?>(null) }
    var deleting by remember { mutableStateOf<SavedTrack?>(null) }
    var renaming by remember { mutableStateOf<SavedTrack?>(null) }

    val saveAs = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(GPX_MIME)) { uri ->
        val track = exporting ?: return@rememberLauncherForActivityResult
        exporting = null
        if (uri != null) {
            viewModel.export(track, uri) { ok ->
                Toast.makeText(context, if (ok) R.string.tracks_saved else R.string.tracks_save_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        Surface(shadowElevation = 4.dp) {
            Row(
                modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                MapButton(SentrekIcons.Back, stringResource(R.string.tracks_back), onBack)
                Text(stringResource(R.string.tracks_title), style = MaterialTheme.typography.headlineSmall)
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
                    )
                }
                item { Box(Modifier.navigationBarsPadding()) }
            }
        }
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

@Composable
private fun TrackCard(
    track: SavedTrack,
    isShown: Boolean,
    onToggleMap: () -> Unit,
    onShare: () -> Unit,
    onSave: () -> Unit,
    onDelete: () -> Unit,
    onRename: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = TouchTarget)
                    .clickable(onClickLabel = stringResource(R.string.tracks_rename), onClick = onRename),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    track.name,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.tracks_rename))
            }
            Text(
                stringResource(
                    R.string.tracks_stats,
                    Format.distance(track.distance),
                    Format.duration(track.duration),
                    Format.meters(track.ascent),
                    Format.meters(track.descent),
                ),
                style = MaterialTheme.typography.bodyLarge,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val buttonModifier = Modifier.weight(1f).heightIn(min = TouchTarget)
                val padding = PaddingValues(horizontal = 4.dp)
                if (isShown) {
                    Button(onClick = onToggleMap, modifier = buttonModifier, contentPadding = padding) {
                        Text(stringResource(R.string.tracks_hide))
                    }
                } else {
                    OutlinedButton(onClick = onToggleMap, modifier = buttonModifier, contentPadding = padding) {
                        Text(stringResource(R.string.tracks_show))
                    }
                }
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
