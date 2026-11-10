package app.sentrek.record

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import app.sentrek.feature.record.R
import app.sentrek.ui.SentrekIcons
import app.sentrek.ui.TouchTarget

/**
 * A route's or track's name, note and photos, as for a place. The name starts selected, so
 * typing replaces it; left empty it stays as it was. [onConfirm] gets the three. Photos taken
 * here and then dropped (or the whole dialog) are deleted again; the caller deletes the ones
 * the track had and lost (see [TrackRepository.edit]).
 */
@Composable
fun TrackDetailsDialog(
    title: String,
    confirmLabel: String,
    initialName: String,
    initialNote: String,
    initialPhotos: List<String>,
    onConfirm: (name: String, note: String, photos: List<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(initialName, TextRange(0, initialName.length)))
    }
    var note by rememberSaveable { mutableStateOf(initialNote) }
    var photos by rememberSaveable { mutableStateOf(initialPhotos) }
    var added by rememberSaveable { mutableStateOf(emptyList<String>()) }
    val store = rememberPhotoStore()
    val dismiss = {
        store.delete(added)
        onDismiss()
    }
    AlertDialog(
        onDismissRequest = dismiss,
        // Ends above the keyboard, so it doesn't slide up with each letter typed in the note.
        modifier = Modifier.windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 44.dp, vertical = 16.dp),
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.tracks_name)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text(stringResource(R.string.places_note)) },
                    minLines = 3,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier.fillMaxWidth(),
                )
                PhotoEditor(
                    photos = photos,
                    onAdd = { photos = photos + it; added = added + it },
                    onRemove = { photos = photos - it },
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    store.delete(added - photos.toSet())
                    onConfirm(name.text, note, photos)
                },
                modifier = Modifier.heightIn(min = TouchTarget),
            ) { Text(confirmLabel) }
        },
        dismissButton = {
            TextButton(onClick = dismiss, modifier = Modifier.heightIn(min = TouchTarget)) {
                Text(stringResource(R.string.tracks_cancel))
            }
        },
    )
}

/**
 * A route's note and photos. With [onExpand], as the places ahead on the trail card: closed,
 * one line (the note's start, how many photos); a tap opens the whole note and the photos,
 * another closes them, and the caller remembers which. Without it, all of it, always.
 * [maxHeight] keeps a long note from taking the screen (it scrolls). Nothing when there
 * are neither note nor photos.
 */
@Composable
fun TrackNote(
    note: String,
    photos: List<String>,
    expanded: Boolean,
    onExpand: ((Boolean) -> Unit)?,
    photoSize: Dp,
    modifier: Modifier = Modifier,
    maxHeight: Dp = Dp.Infinity,
    style: TextStyle = MaterialTheme.typography.bodyLarge,
) {
    if (note.isEmpty() && photos.isEmpty()) return
    val open = expanded || onExpand == null
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (onExpand != null) HorizontalDivider(color = LocalContentColor.current.copy(alpha = 0.2f))
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = if (onExpand != null) TouchTarget else 0.dp)
                .then(
                    if (onExpand == null) Modifier else Modifier.clickable(
                        onClickLabel = stringResource(if (expanded) R.string.tracks_note_less else R.string.tracks_note_more),
                        onClick = { onExpand(!expanded) },
                    ),
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(
                Modifier
                    .weight(1f)
                    .heightIn(max = if (open) maxHeight else Dp.Infinity)
                    .then(if (open && maxHeight != Dp.Infinity) Modifier.verticalScroll(rememberScrollState()) else Modifier),
            ) {
                if (note.isNotEmpty()) {
                    Text(
                        note,
                        style = style,
                        maxLines = if (open) Int.MAX_VALUE else 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                } else if (!open) {
                    Text(pluralStringResource(R.plurals.tracks_photos, photos.size, photos.size), style = style)
                }
            }
            if (onExpand != null) {
                if (!open && photos.isNotEmpty() && note.isNotEmpty()) {
                    Icon(SentrekIcons.Image, contentDescription = null, modifier = Modifier.size(20.dp))
                    Text("${photos.size}", style = MaterialTheme.typography.labelLarge)
                }
                Icon(if (expanded) SentrekIcons.ExpandLess else SentrekIcons.ExpandMore, contentDescription = null)
            }
        }
        if (open && photos.isNotEmpty()) PhotoStrip(photos, size = photoSize)
    }
}
