package app.sentrek.record

import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import app.sentrek.feature.record.R
import app.sentrek.geo.gpx.Gpx
import app.sentrek.geo.place.Place
import app.sentrek.ui.TouchTarget
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface ShareEntryPoint {
    fun backups(): BackupRepository
    fun tracks(): TrackRepository
}

/** What is about to be shared, once the user picks how. */
class Sharing {
    internal var place by mutableStateOf<Place?>(null)
    internal var track by mutableStateOf<SavedTrack?>(null)

    fun share(place: Place) {
        this.place = place
    }

    fun share(track: SavedTrack) {
        this.track = track
    }
}

/**
 * Share a place or a track: as a map file any app opens (GPX), or as a Sentrek file with the
 * photos and notes, which Sentrek on another phone opens as it was. A place can also go as a
 * message (text, map link and photos). Call [ShareDialog] where the dialogs are.
 */
@Composable
fun rememberSharing(): Sharing = remember { Sharing() }

@Composable
fun ShareDialog(sharing: Sharing) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val entry = remember { EntryPointAccessors.fromApplication(context.applicationContext, ShareEntryPoint::class.java) }
    val place = sharing.place
    val track = sharing.track
    if (place == null && track == null) return
    val close = {
        sharing.place = null
        sharing.track = null
    }
    val title = place?.name ?: track?.name.orEmpty()
    val options = buildList {
        if (place != null) {
            add(Triple(R.string.share_message, R.string.share_message_hint) { sharePlace(context, place) })
            add(Triple(R.string.share_gpx, R.string.share_gpx_hint) { scope.launch { placeGpx(context, place)?.let { send(context, it, GPX_MIME, title) } } })
            add(Triple(R.string.share_sentrek, R.string.share_sentrek_place_hint) {
                scope.launch { entry.backups().sharePlace(place)?.let { send(context, it, ZIP_MIME, title) } ?: failed(context) }
            })
        }
        if (track != null) {
            add(Triple(R.string.share_gpx, R.string.share_gpx_hint) {
                scope.launch { entry.tracks().shareCopy(track)?.let { send(context, it, GPX_MIME, title) } ?: failed(context) }
            })
            add(Triple(R.string.share_sentrek, R.string.share_sentrek_track_hint) {
                scope.launch { entry.backups().shareTrack(track)?.let { send(context, it, ZIP_MIME, title) } ?: failed(context) }
            })
        }
    }
    AlertDialog(
        onDismissRequest = close,
        title = { Text(stringResource(R.string.share_title, title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                for ((label, hint, action) in options) {
                    OutlinedButton(
                        onClick = { close(); action() },
                        modifier = Modifier.fillMaxWidth().heightIn(min = TouchTarget),
                    ) {
                        Column(Modifier.padding(vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(stringResource(label), fontWeight = FontWeight.Bold)
                            Text(stringResource(hint), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = close, modifier = Modifier.heightIn(min = TouchTarget)) { Text(stringResource(R.string.tracks_cancel)) }
        },
    )
}

/** The place as a one-waypoint GPX, named after it. */
private suspend fun placeGpx(context: Context, place: Place): File? = withContext(Dispatchers.IO) {
    runCatching {
        val dir = File(context.cacheDir, "share").apply { deleteRecursively(); mkdirs() }
        val name = place.name.replace(Regex("""[\\/:*?"<>|,]+"""), "-").trim('-', ' ').ifEmpty { "Place" }
        File(dir, "$name.gpx").also { f ->
            f.bufferedWriter().use { Gpx.write(it, place.name, emptyList(), "Sentrek", listOf(PlaceRepository.waypoint(place))) }
        }
    }.onFailure { Log.w("ShareUi", "Cannot write ${place.name}", it) }.getOrNull()
}

private fun send(context: Context, file: File, mime: String, title: String) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.tracks", file)
    val send = Intent(Intent.ACTION_SEND)
        .setType(mime)
        .putExtra(Intent.EXTRA_STREAM, uri)
        .putExtra(Intent.EXTRA_SUBJECT, title)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(send, title))
}

private fun failed(context: Context) {
    Toast.makeText(context, R.string.share_failed, Toast.LENGTH_SHORT).show()
}

private const val GPX_MIME = "application/gpx+xml"
private const val ZIP_MIME = "application/zip"
