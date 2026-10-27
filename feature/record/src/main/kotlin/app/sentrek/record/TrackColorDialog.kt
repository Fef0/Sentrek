package app.sentrek.record

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.sentrek.feature.record.R
import app.sentrek.ui.TouchTarget

/**
 * Picks a track colour: one of [TrackColors.palette] with a single tap, or any colour from the
 * saturation/brightness square and the hue bar below, confirmed with "Use colour".
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TrackColorDialog(current: Int, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    val start = remember(current) { FloatArray(3).also { android.graphics.Color.colorToHSV(current, it) } }
    var hue by remember(current) { mutableFloatStateOf(start[0]) }
    var saturation by remember(current) { mutableFloatStateOf(start[1]) }
    var value by remember(current) { mutableFloatStateOf(start[2]) }
    val custom = Color.hsv(hue, saturation, value)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.tracks_color)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    for (color in TrackColors.palette) {
                        ColorSwatch(color, selected = color == current, size = TouchTarget, onClick = { onPick(color) })
                    }
                }
                Text(stringResource(R.string.tracks_color_custom), style = MaterialTheme.typography.titleSmall)
                SaturationValueBox(hue, saturation, value) { s, v -> saturation = s; value = v }
                HueBar(hue) { hue = it }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.size(TouchTarget * 0.6f).clip(CircleShape).border(1.dp, Color.Gray, CircleShape)) {
                        Canvas(Modifier.matchParentSize()) { drawRect(custom) }
                    }
                    Text(
                        String.format("#%06X", custom.toArgb() and 0xFFFFFF),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f),
                    )
                    Button(onClick = { onPick(custom.toArgb()) }, modifier = Modifier.heightIn(min = TouchTarget)) {
                        Text(stringResource(R.string.tracks_color_use))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = TouchTarget)) {
                Text(stringResource(R.string.tracks_cancel))
            }
        },
    )
}

/** Saturation left to right, brightness top to bottom, for the current hue. */
@Composable
private fun SaturationValueBox(hue: Float, saturation: Float, value: Float, onChange: (Float, Float) -> Unit) {
    val pick = { offset: Offset, width: Float, height: Float ->
        onChange((offset.x / width).coerceIn(0f, 1f), 1f - (offset.y / height).coerceIn(0f, 1f))
    }
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(160.dp)
            .clip(RoundedCornerShape(8.dp))
            .pointerInput(Unit) { detectTapGestures { pick(it, size.width.toFloat(), size.height.toFloat()) } }
            .pointerInput(Unit) {
                detectDragGestures { change, _ -> pick(change.position, size.width.toFloat(), size.height.toFloat()) }
            },
    ) {
        drawRect(Brush.horizontalGradient(listOf(Color.White, Color.hsv(hue, 1f, 1f))))
        drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
        val center = Offset(saturation * size.width, (1f - value) * size.height)
        drawCircle(Color.White, radius = 12.dp.toPx(), center = center, style = Stroke(3.dp.toPx()))
        drawCircle(Color.Black, radius = 14.dp.toPx(), center = center, style = Stroke(1.dp.toPx()))
    }
}

/** All hues, left to right. */
@Composable
private fun HueBar(hue: Float, onChange: (Float) -> Unit) {
    val hues = remember { (0..6).map { Color.hsv(it * 60f % 360f, 1f, 1f) } }
    val pick = { x: Float, width: Float -> onChange((x / width).coerceIn(0f, 1f) * 359.9f) }
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(TouchTarget * 0.6f)
            .clip(RoundedCornerShape(8.dp))
            .pointerInput(Unit) { detectTapGestures { pick(it.x, size.width.toFloat()) } }
            .pointerInput(Unit) { detectDragGestures { change, _ -> pick(change.position.x, size.width.toFloat()) } },
    ) {
        drawRect(Brush.horizontalGradient(hues))
        val x = hue / 360f * size.width
        drawLine(Color.White, Offset(x, 0f), Offset(x, size.height), strokeWidth = 4.dp.toPx())
        drawLine(Color.Black, Offset(x - 3.dp.toPx(), 0f), Offset(x - 3.dp.toPx(), size.height), strokeWidth = 1.dp.toPx())
        drawLine(Color.Black, Offset(x + 3.dp.toPx(), 0f), Offset(x + 3.dp.toPx(), size.height), strokeWidth = 1.dp.toPx())
    }
}

/** A round colour sample; tapping it calls [onClick]. */
@Composable
fun ColorSwatch(color: Int, selected: Boolean, size: Dp, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        modifier = modifier
            .size(size)
            .border(2.dp, if (selected) MaterialTheme.colorScheme.onSurface else Color.Transparent, CircleShape),
        shape = CircleShape,
        color = Color(color),
    ) {
        if (selected) {
            Box(contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Check, contentDescription = null, tint = Color.White)
            }
        }
    }
}
