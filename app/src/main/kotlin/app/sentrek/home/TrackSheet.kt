package app.sentrek.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.sentrek.R
import app.sentrek.geo.route.HikingTime
import app.sentrek.record.ColorSwatch
import app.sentrek.record.SavedTrack
import app.sentrek.record.TrackColorDialog
import app.sentrek.ui.Format
import app.sentrek.ui.TouchTarget

/** A shown track, long-pressed on the map: follow it, change its colour, or take it off the map. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackSheet(
    track: SavedTrack,
    isFollowed: Boolean,
    onFollow: () -> Unit,
    onStopFollowing: () -> Unit,
    onColor: (Int) -> Unit,
    onHide: () -> Unit,
    onDismiss: () -> Unit,
) {
    var choosingColor by remember { mutableStateOf(false) }
    val wide = Modifier.fillMaxWidth().heightIn(min = TouchTarget)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ColorSwatch(track.color, selected = false, size = TouchTarget * 0.6f, onClick = { choosingColor = true })
                Text(track.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            }
            val time = if (track.duration > 0) {
                Format.duration(track.duration)
            } else {
                Format.estimate(HikingTime.millis(track.distance, track.ascent, track.descent))
            }
            Text(
                "${Format.distance(track.distance)} · $time · ↑ ${Format.meters(track.ascent)} ↓ ${Format.meters(track.descent)}",
                style = MaterialTheme.typography.bodyLarge,
            )
            if (isFollowed) {
                Button(onClick = onStopFollowing, modifier = wide) { Text(stringResource(R.string.track_stop_following)) }
            } else {
                Button(onClick = onFollow, modifier = wide) { Text(stringResource(R.string.track_follow)) }
            }
            OutlinedButton(onClick = { choosingColor = true }, modifier = wide) { Text(stringResource(R.string.track_color)) }
            OutlinedButton(onClick = onHide, modifier = wide) { Text(stringResource(R.string.track_hide)) }
        }
    }
    if (choosingColor) {
        TrackColorDialog(
            current = track.color,
            onPick = { onColor(it); choosingColor = false },
            onDismiss = { choosingColor = false },
        )
    }
}
