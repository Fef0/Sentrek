package app.sentrek.record

import android.content.ActivityNotFoundException
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.sentrek.feature.record.R
import app.sentrek.ui.SentrekIcons
import app.sentrek.ui.TouchTarget
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.launch

@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface PhotoEntryPoint {
    fun photos(): PhotoStore
}

/** For composables that aren't behind a view model: the place dialog, sheet and card. */
@Composable
internal fun rememberPhotoStore(): PhotoStore {
    val context = LocalContext.current
    return remember { photoStore(context) }
}

internal fun photoStore(context: Context): PhotoStore =
    EntryPointAccessors.fromApplication(context.applicationContext, PhotoEntryPoint::class.java).photos()

/** One stored photo, cropped to fill; grey while it loads. [side] in px for decoding. */
@Composable
fun PlacePhoto(name: String, side: Int, modifier: Modifier = Modifier, contentScale: ContentScale = ContentScale.Crop) {
    val store = rememberPhotoStore()
    val bitmap by produceState<android.graphics.Bitmap?>(null, name, side) { value = store.thumbnail(name, side) }
    Box(modifier.background(Color.Gray.copy(alpha = 0.3f))) {
        bitmap?.let {
            Image(it.asImageBitmap(), contentDescription = null, contentScale = contentScale, modifier = Modifier.fillMaxSize())
        }
    }
}

/** The photos in a row; a tap opens them full screen. */
@Composable
fun PhotoStrip(photos: List<String>, size: Dp, modifier: Modifier = Modifier) {
    var viewing by remember { mutableStateOf<Int?>(null) }
    LazyRow(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(photos.size) { i ->
            PlacePhoto(
                photos[i],
                side = THUMBNAIL_PX,
                modifier = Modifier.size(size).clip(RoundedCornerShape(8.dp)).clickable { viewing = i },
            )
        }
    }
    viewing?.let { PhotoViewer(photos, it, onDismiss = { viewing = null }) }
}

/** Full screen, swipe for the others, tap to close. */
@Composable
fun PhotoViewer(photos: List<String>, start: Int, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val pager = rememberPagerState(initialPage = start) { photos.size }
        Box(Modifier.fillMaxSize().background(Color.Black).clickable(onClick = onDismiss)) {
            HorizontalPager(pager, Modifier.fillMaxSize()) { page ->
                PlacePhoto(photos[page], side = FULL_PX, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            }
            if (photos.size > 1) {
                Text(
                    "${pager.currentPage + 1} / ${photos.size}",
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(32.dp),
                )
            }
        }
    }
}

/**
 * The photos of a place being edited, with Camera and Gallery buttons. New photos are stored
 * at once and reported through [onAdd]; the caller deletes them again if the edit is dropped.
 */
@Composable
fun PhotoEditor(photos: List<String>, onAdd: (String) -> Unit, onRemove: (String) -> Unit) {
    val context = LocalContext.current
    val store = rememberPhotoStore()
    val scope = rememberCoroutineScope()
    // Survives the app being killed while the camera is open, as low-memory phones do.
    var capturing by rememberSaveable { mutableStateOf<String?>(null) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { taken ->
        val name = capturing ?: return@rememberLauncherForActivityResult
        capturing = null
        scope.launch {
            // Some camera apps report "cancelled" after writing the photo: trust the file.
            if (store.finishCapture(name)) onAdd(name) else if (taken) failed(context)
        }
    }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(MAX_PICK)) { uris ->
        scope.launch {
            for (uri in uris) store.import(uri)?.let(onAdd) ?: failed(context)
        }
    }
    var viewing by remember { mutableStateOf<Int?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.places_photos), style = MaterialTheme.typography.titleSmall)
        if (photos.isNotEmpty()) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(photos, key = { it }) { name ->
                    Box(Modifier.size(TouchTarget * 1.2f)) {
                        PlacePhoto(
                            name,
                            side = THUMBNAIL_PX,
                            modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp)).clickable { viewing = photos.indexOf(name) },
                        )
                        Surface(
                            onClick = { onRemove(name) },
                            shape = CircleShape,
                            color = Color.Black.copy(alpha = 0.6f),
                            contentColor = Color.White,
                            modifier = Modifier.align(Alignment.TopEnd).padding(2.dp).size(28.dp),
                        ) {
                            Icon(SentrekIcons.Close, stringResource(R.string.places_photo_remove), Modifier.padding(4.dp))
                        }
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = {
                    val name = store.newName()
                    capturing = name
                    try {
                        camera.launch(store.uri(name))
                    } catch (_: ActivityNotFoundException) {
                        capturing = null
                        Toast.makeText(context, R.string.places_no_camera, Toast.LENGTH_LONG).show()
                    }
                },
                modifier = Modifier.weight(1f).heightIn(min = TouchTarget),
                contentPadding = PaddingValues(horizontal = 8.dp),
            ) {
                Icon(SentrekIcons.Camera, contentDescription = null, modifier = Modifier.padding(end = 6.dp))
                Text(stringResource(R.string.places_camera), maxLines = 1, softWrap = false)
            }
            OutlinedButton(
                onClick = { gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                modifier = Modifier.weight(1f).heightIn(min = TouchTarget),
                contentPadding = PaddingValues(horizontal = 8.dp),
            ) {
                Icon(SentrekIcons.Image, contentDescription = null, modifier = Modifier.padding(end = 6.dp))
                Text(stringResource(R.string.places_gallery), maxLines = 1, softWrap = false)
            }
        }
    }
    viewing?.let { PhotoViewer(photos, it, onDismiss = { viewing = null }) }
}

private fun failed(context: Context) {
    Toast.makeText(context, R.string.places_photo_failed, Toast.LENGTH_SHORT).show()
}

private const val THUMBNAIL_PX = 256
private const val FULL_PX = 1600
private const val MAX_PICK = 10
