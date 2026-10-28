package app.sentrek.record

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.sentrek.feature.record.R
import app.sentrek.geo.LatLon
import app.sentrek.geo.place.Place
import app.sentrek.geo.place.PlaceKind
import app.sentrek.map.PlaceLayers
import app.sentrek.ui.Format
import app.sentrek.ui.TouchTarget
import java.util.Locale

@Composable
fun placeKindLabel(kind: PlaceKind): String = stringResource(
    when (kind) {
        PlaceKind.Star -> R.string.place_kind_star
        PlaceKind.Camp -> R.string.place_kind_camp
        PlaceKind.Water -> R.string.place_kind_water
        PlaceKind.Shelter -> R.string.place_kind_shelter
        PlaceKind.View -> R.string.place_kind_viewpoint
        PlaceKind.Peak -> R.string.place_kind_peak
        PlaceKind.Parking -> R.string.place_kind_parking
        PlaceKind.Food -> R.string.place_kind_food
        PlaceKind.Danger -> R.string.place_kind_danger
    },
)

/** The category symbol in white on a disc of the place colour, as on the map. */
@Composable
fun PlaceIcon(kind: PlaceKind, color: Int, size: Dp, modifier: Modifier = Modifier) {
    Surface(modifier = modifier.size(size), shape = CircleShape, color = Color(color)) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                painterResource(PlaceLayers.icon(kind)),
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(size * 0.55f),
            )
        }
    }
}

/** In the format chosen in Settings. */
fun formatCoordinates(p: LatLon): String = Format.coordinates(p.lat, p.lon)

/**
 * New or edited place: name, category, note, photos and colour. [initial] carries the position
 * (and for a new place a suggested name and kind); [onSave] gets the edited copy.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PlaceDialog(title: String, initial: Place, onSave: (Place) -> Unit, onDismiss: () -> Unit) {
    var name by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(initial.name, TextRange(0, initial.name.length)))
    }
    var note by rememberSaveable { mutableStateOf(initial.note) }
    var kind by rememberSaveable { mutableStateOf(initial.kind) }
    var color by rememberSaveable { mutableStateOf(initial.color) }
    var photos by rememberSaveable { mutableStateOf(initial.photos) }
    // Taken in this dialog: deleted again if it's dropped, or if removed before saving.
    var added by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var choosingColor by remember { mutableStateOf(false) }
    val store = rememberPhotoStore()
    val edited = initial.copy(name = name.text.trim().ifEmpty { initial.name }, kind = kind, note = note, color = color, photos = photos)
    val dismiss = {
        store.delete(added)
        onDismiss()
    }

    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(title) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(formatCoordinates(initial.position), style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.places_name)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(stringResource(R.string.places_category), style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (k in PlaceKind.entries) {
                        KindChip(k, color ?: k.color, selected = k == kind, onClick = { kind = k })
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.places_color), style = MaterialTheme.typography.titleSmall)
                    ColorSwatch(edited.displayColor, selected = false, size = TouchTarget * 0.6f, onClick = { choosingColor = true })
                    if (color != null) {
                        TextButton(onClick = { color = null }, modifier = Modifier.heightIn(min = TouchTarget)) {
                            Text(stringResource(R.string.places_color_reset))
                        }
                    }
                }
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text(stringResource(R.string.places_note)) },
                    minLines = 2,
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
                    onSave(edited)
                },
                modifier = Modifier.heightIn(min = TouchTarget),
            ) {
                Text(stringResource(R.string.places_save))
            }
        },
        dismissButton = {
            TextButton(onClick = dismiss, modifier = Modifier.heightIn(min = TouchTarget)) {
                Text(stringResource(R.string.tracks_cancel))
            }
        },
    )
    if (choosingColor) {
        TrackColorDialog(
            current = edited.displayColor,
            onPick = { color = it; choosingColor = false },
            onDismiss = { choosingColor = false },
        )
    }
}

@Composable
private fun KindChip(kind: PlaceKind, color: Int, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Surface(
        onClick = onClick,
        shape = shape,
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
        modifier = Modifier
            .heightIn(min = TouchTarget * 0.75f)
            .border(if (selected) 2.dp else 1.dp, if (selected) MaterialTheme.colorScheme.onSurface else Color.Gray, shape),
    ) {
        Row(
            modifier = Modifier.padding(start = 6.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            PlaceIcon(kind, color, TouchTarget * 0.5f)
            Text(placeKindLabel(kind), style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** A long-pressed place on the map. [distance] from the user in metres, when known. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaceSheet(
    place: Place,
    distance: Double?,
    onEdit: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
    /** Null where there is no map to point the way on. */
    onGoTo: (() -> Unit)? = null,
) {
    val wide = Modifier.fillMaxWidth().heightIn(min = TouchTarget)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PlaceIcon(place.kind, place.displayColor, TouchTarget * 0.7f)
                Column {
                    Text(place.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text(placeKindLabel(place.kind), style = MaterialTheme.typography.titleMedium)
                }
            }
            if (place.note.isNotEmpty()) Text(place.note, style = MaterialTheme.typography.bodyLarge)
            if (place.photos.isNotEmpty()) PhotoStrip(place.photos, size = TouchTarget * 1.6f)
            SelectionContainer {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Fact(stringResource(R.string.places_coordinates), formatCoordinates(place.position))
                    place.position.ele?.let { Fact(stringResource(R.string.places_elevation), Format.elevation(it)) }
                    distance?.let { Fact(stringResource(R.string.places_distance), Format.distance(it)) }
                }
            }
            if (onGoTo != null) Button(onClick = onGoTo, modifier = wide) { Text(stringResource(R.string.places_go_to)) }
            if (onGoTo != null) {
                OutlinedButton(onClick = onEdit, modifier = wide) { Text(stringResource(R.string.places_edit)) }
            } else {
                Button(onClick = onEdit, modifier = wide) { Text(stringResource(R.string.places_edit)) }
            }
            OutlinedButton(onClick = onShare, modifier = wide) { Text(stringResource(R.string.places_share)) }
            OutlinedButton(onClick = onDelete, modifier = wide) {
                Text(stringResource(R.string.places_delete), color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun Fact(label: String, value: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(0.4f))
        Text(value, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, modifier = Modifier.weight(0.6f))
    }
}

@Composable
fun DeletePlaceDialog(place: Place, onDelete: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.places_delete_title)) },
        text = { Text(place.name) },
        confirmButton = {
            Button(
                onClick = onDelete,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.heightIn(min = TouchTarget),
            ) { Text(stringResource(R.string.places_delete)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = TouchTarget)) {
                Text(stringResource(R.string.tracks_cancel))
            }
        },
    )
}

/** Sends the place as text (name, note, coordinates and a map link anyone can open), with its photos. */
fun sharePlace(context: Context, place: Place) {
    val lat = String.format(Locale.ROOT, "%.5f", place.position.lat)
    val lon = String.format(Locale.ROOT, "%.5f", place.position.lon)
    val text = listOf(
        place.name,
        place.note,
        "$lat, $lon",
        "https://www.openstreetmap.org/?mlat=$lat&mlon=$lon#map=16/$lat/$lon",
    ).filter { it.isNotEmpty() }.joinToString("\n")
    val store = photoStore(context)
    val photos = place.photos.filter { store.file(it).exists() }.map(store::uri)
    val send = when {
        photos.isEmpty() -> Intent(Intent.ACTION_SEND).setType("text/plain")
        photos.size == 1 -> Intent(Intent.ACTION_SEND).setType("image/jpeg").putExtra(Intent.EXTRA_STREAM, photos[0])
        else -> Intent(Intent.ACTION_SEND_MULTIPLE).setType("image/jpeg").putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(photos))
    }
        .putExtra(Intent.EXTRA_SUBJECT, place.name)
        .putExtra(Intent.EXTRA_TEXT, text)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    // The chooser passes the grant on only when the photos are also its clip data.
    if (photos.isNotEmpty()) {
        send.clipData = ClipData.newRawUri(null, photos[0]).apply { photos.drop(1).forEach { addItem(ClipData.Item(it)) } }
    }
    context.startActivity(Intent.createChooser(send, place.name))
}
