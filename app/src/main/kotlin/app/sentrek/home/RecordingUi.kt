package app.sentrek.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.sentrek.R
import app.sentrek.record.Recording
import app.sentrek.ui.Format
import app.sentrek.ui.TouchTarget
import kotlinx.coroutines.delay

/** Live numbers while recording: time (without pauses), distance, climb. */
@Composable
fun RecordingStats(recording: Recording, modifier: Modifier = Modifier) {
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            value = System.currentTimeMillis()
            delay(1_000)
        }
    }
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
        // See-through, so not matched to a theme colour: name the text colour.
        contentColor = MaterialTheme.colorScheme.onSurface,
        shadowElevation = 4.dp,
    ) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 8.dp)) {
            if (recording.isPaused) {
                Stat(stringResource(R.string.record_paused), Format.duration(recording.activeMillis(now)), Modifier.weight(1f), MaterialTheme.colorScheme.primary)
            } else {
                Stat(stringResource(R.string.record_time), Format.duration(recording.activeMillis(now)), Modifier.weight(1f))
            }
            Stat(stringResource(R.string.record_distance), Format.distance(recording.distance), Modifier.weight(1f))
            Stat(stringResource(R.string.record_ascent), "↑ " + Format.meters(recording.ascent), Modifier.weight(1f))
        }
    }
}

@Composable
private fun Stat(label: String, value: String, modifier: Modifier, labelColor: Color = Color.Unspecified) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, maxLines = 1)
        Text(label, style = MaterialTheme.typography.labelLarge, color = labelColor)
    }
}

/**
 * Opened from the Stop / Paused button. Closing the dialog leaves the recording as it is,
 * so a bumped button doesn't end a hike.
 */
@Composable
fun RecordingDialog(
    paused: Boolean,
    onPauseResume: () -> Unit,
    onSave: () -> Unit,
    onDiscard: () -> Unit,
    onDismiss: () -> Unit,
) {
    val wide = Modifier.fillMaxWidth().heightIn(min = TouchTarget)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (paused) R.string.record_paused_title else R.string.record_stop_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (paused) {
                    Button(onClick = onPauseResume, modifier = wide) { Text(stringResource(R.string.record_resume)) }
                    OutlinedButton(onClick = onSave, modifier = wide) { Text(stringResource(R.string.record_save)) }
                } else {
                    Button(onClick = onSave, modifier = wide) { Text(stringResource(R.string.record_save)) }
                    OutlinedButton(onClick = onPauseResume, modifier = wide) { Text(stringResource(R.string.record_pause)) }
                    OutlinedButton(onClick = onDismiss, modifier = wide) { Text(stringResource(R.string.record_continue)) }
                }
                TextButton(onClick = onDiscard, modifier = wide) {
                    Text(stringResource(R.string.record_discard), color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {},
    )
}
