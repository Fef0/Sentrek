package app.sentrek.record

import android.Manifest
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.sentrek.feature.record.R
import app.sentrek.geo.BoundingBox
import app.sentrek.geo.Geo
import app.sentrek.geo.LatLon
import app.sentrek.geo.place.Place
import app.sentrek.ui.Format
import app.sentrek.ui.MapButton
import app.sentrek.ui.SentrekIcons
import app.sentrek.ui.TouchTarget
import app.sentrek.ui.safeTopPadding

/** Saved places, nearest first when we know where the user is: show, edit, share, delete; save the current spot. */
@Composable
fun PlacesScreen(
    onBack: () -> Unit,
    onShowOnMap: (BoundingBox) -> Unit,
    viewModel: PlacesViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val places by viewModel.places.collectAsStateWithLifecycle()
    val here by viewModel.here.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<Place?>(null) }
    var creating by remember { mutableStateOf<Place?>(null) }
    var deleting by remember { mutableStateOf<Place?>(null) }
    var waitingForFix by remember { mutableStateOf(false) }
    val sharing = rememberSharing()
    val unnamed = stringResource(R.string.places_unnamed)

    val saveHere = {
        waitingForFix = true
        viewModel.draftHere(unnamed) { draft ->
            waitingForFix = false
            if (draft == null) Toast.makeText(context, R.string.places_no_gps, Toast.LENGTH_LONG).show() else creating = draft
        }
    }
    val locationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        viewModel.onPermissionChanged()
        if (viewModel.hasLocationPermission()) saveHere()
    }
    val exportTo = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/gpx+xml")) { uri ->
        if (uri != null) {
            viewModel.export(uri) { ok ->
                Toast.makeText(context, if (ok) R.string.places_exported else R.string.places_export_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    // Nearest first while we have a fix; newest first otherwise.
    val sorted = remember(places, here) {
        val h = here
        if (h == null) places.sortedByDescending { it.time } else places.sortedBy { Geo.distance(h, it.position) }
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
                    stringResource(R.string.places_title),
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.weight(1f),
                )
                OutlinedButton(
                    onClick = { exportTo.launch(PlaceRepository.EXPORT_FILE_NAME) },
                    enabled = places.isNotEmpty(),
                    modifier = Modifier.heightIn(min = TouchTarget),
                ) { Text(stringResource(R.string.places_export)) }
            }
        }

        Button(
            onClick = {
                if (viewModel.hasLocationPermission()) {
                    saveHere()
                } else {
                    locationPermission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                }
            },
            enabled = !waitingForFix,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp).heightIn(min = TouchTarget),
        ) {
            Text(stringResource(if (waitingForFix) R.string.places_waiting_gps else R.string.places_save_here))
        }

        if (places.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.places_empty), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(sorted, key = { it.id }) { place ->
                    PlaceCard(
                        place = place,
                        distance = here?.let { Geo.distance(it, place.position) },
                        onShow = { onShowOnMap(around(place.position)) },
                        onEdit = { editing = place },
                        onShare = { sharing.share(place) },
                        onDelete = { deleting = place },
                    )
                }
                item { Box(Modifier.navigationBarsPadding()) }
            }
        }
    }

    creating?.let { draft ->
        PlaceDialog(
            title = stringResource(R.string.places_new),
            initial = draft,
            onSave = { viewModel.add(it); creating = null },
            onDismiss = { creating = null },
        )
    }
    editing?.let { place ->
        PlaceDialog(
            title = stringResource(R.string.places_edit),
            initial = place,
            onSave = { viewModel.update(it); editing = null },
            onDismiss = { editing = null },
        )
    }
    deleting?.let { place ->
        DeletePlaceDialog(place, onDelete = { viewModel.delete(place); deleting = null }, onDismiss = { deleting = null })
    }
    ShareDialog(sharing)
}

/** About 1 km around [p]: the map zooms in close enough to see the trails. */
fun around(p: LatLon): BoundingBox {
    val d = 0.004
    return BoundingBox((p.lat - d).coerceAtLeast(-90.0), (p.lon - d).coerceAtLeast(-180.0), (p.lat + d).coerceAtMost(90.0), (p.lon + d).coerceAtMost(180.0))
}

@Composable
private fun PlaceCard(
    place: Place,
    distance: Double?,
    onShow: () -> Unit,
    onEdit: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = TouchTarget).clickable(onClick = onShow),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                PlaceIcon(place.kind, place.displayColor, TouchTarget * 0.7f)
                Column(Modifier.weight(1f)) {
                    Text(place.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(
                        listOfNotNull(placeKindLabel(place.kind), distance?.let(Format::distance)).joinToString(" · "),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
            if (place.note.isNotEmpty()) Text(place.note, style = MaterialTheme.typography.bodyLarge)
            if (place.photos.isNotEmpty()) PhotoStrip(place.photos, size = TouchTarget * 1.2f)
            val buttonModifier = Modifier.weight(1f).heightIn(min = TouchTarget)
            val padding = PaddingValues(horizontal = 4.dp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onShow, modifier = buttonModifier, contentPadding = padding) {
                    Text(stringResource(R.string.places_show))
                }
                OutlinedButton(onClick = onEdit, modifier = buttonModifier, contentPadding = padding) {
                    Text(stringResource(R.string.places_edit_short))
                }
                OutlinedButton(onClick = onShare, modifier = buttonModifier, contentPadding = padding) {
                    Text(stringResource(R.string.places_share))
                }
                OutlinedButton(onClick = onDelete, modifier = buttonModifier, contentPadding = padding) {
                    Text(stringResource(R.string.places_delete), color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}
