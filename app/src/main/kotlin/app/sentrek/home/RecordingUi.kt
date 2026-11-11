package app.sentrek.home

import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import app.sentrek.record.Breadcrumbs
import app.sentrek.record.Recording
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.sentrek.ui.Format
import app.sentrek.ui.TouchTarget
import kotlinx.coroutines.delay

/** Live numbers while recording: time (without pauses), distance, climb; on one line when [compact]. */
@Composable
fun RecordingNumbers(recording: Recording, modifier: Modifier = Modifier, compact: Boolean = false) {
    // To the minute: seconds tell nothing on a hike, and ticking each second redrew the panel
    // 60 times as often. Woken as the next minute starts; not at all while paused.
    val now by produceState(System.currentTimeMillis(), recording.startTime, recording.pausedMillis, recording.pausedSince) {
        while (true) {
            value = System.currentTimeMillis()
            if (recording.isPaused) break
            delay(60_000 - recording.activeMillis(value) % 60_000)
        }
    }
    if (compact) {
        Text(
            stringResource(
                if (recording.isPaused) R.string.record_line_paused else R.string.record_line,
                Format.hoursMinutes(recording.activeMillis(now)),
                Format.distance(recording.distance),
                Format.elevation(recording.ascent),
            ),
            style = MaterialTheme.typography.titleSmall,
            modifier = modifier,
        )
        return
    }
    Row(modifier) {
        if (recording.isPaused) {
            Stat(stringResource(R.string.record_paused), Format.hoursMinutes(recording.activeMillis(now)), Modifier.weight(1f), MaterialTheme.colorScheme.primary)
        } else {
            Stat(stringResource(R.string.record_time), Format.hoursMinutes(recording.activeMillis(now)), Modifier.weight(1f))
        }
        Stat(stringResource(R.string.record_distance), Format.distance(recording.distance), Modifier.weight(1f))
        Stat(stringResource(R.string.record_ascent), "↑ " + Format.elevation(recording.ascent), Modifier.weight(1f))
    }
}

/** Where the position texts are at, small and muted: it's there to be checked, not to be noticed. */
@Composable
fun BreadcrumbLine(modifier: Modifier = Modifier) {
    val breadcrumbs by Breadcrumbs.status.collectAsStateWithLifecycle()
    val status = breadcrumbs ?: return
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = modifier,
    ) {
        TrackedDot(status)
        Text(
            stringResource(R.string.breadcrumb_chip, status.minutes, Breadcrumbs.statusLine(LocalContext.current, status)),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Red while the position goes out by SMS, grey without signal. Still, to spare the battery:
 * it only pulses for a moment when a text has just gone.
 */
@Composable
private fun TrackedDot(status: Breadcrumbs.Status) {
    val alpha = remember { Animatable(1f) }
    LaunchedEffect(status.lastSent) {
        val sent = status.lastSent ?: return@LaunchedEffect
        if (System.currentTimeMillis() - sent > PULSE_FRESH_MS) return@LaunchedEffect
        repeat(PULSES) {
            alpha.animateTo(0.2f, tween(PULSE_MS))
            alpha.animateTo(1f, tween(PULSE_MS))
        }
    }
    val color = if (status.noSignal) MaterialTheme.colorScheme.onSurfaceVariant else RecordRed
    Box(
        Modifier
            .size(8.dp)
            .graphicsLayer { this.alpha = alpha.value }
            .background(color, CircleShape),
    )
}

private val RecordRed = Color(0xFFE53935)
private const val PULSES = 3
private const val PULSE_MS = 350
/** A text sent longer ago than this (the screen was off) isn't news any more. */
private const val PULSE_FRESH_MS = 60_000L

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
    /** Null until there is a way to go back along. */
    onBackToStart: (() -> Unit)? = null,
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
                    // Pausing has its own button in the bar: fewer choices here, with cold hands.
                    Button(onClick = onSave, modifier = wide) { Text(stringResource(R.string.record_save)) }
                    OutlinedButton(onClick = onDismiss, modifier = wide) { Text(stringResource(R.string.record_continue)) }
                }
                if (onBackToStart != null) {
                    OutlinedButton(onClick = onBackToStart, modifier = wide) { Text(stringResource(R.string.record_back_to_start)) }
                }
                TextButton(onClick = onDiscard, modifier = wide) {
                    Text(stringResource(R.string.record_discard), color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {},
    )
}
