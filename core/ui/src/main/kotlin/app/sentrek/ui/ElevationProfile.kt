package app.sentrek.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp

/**
 * Elevation against distance ([profile]: metres along to metres high), scaled to fill the
 * box, with the highest and lowest heights on the left. Nothing for fewer than two points.
 *
 * With [here] (metres along), the stretch already walked is greyed out and a line and a dot
 * labelled [hereLabel] mark the position.
 */
@Composable
fun ElevationProfile(
    profile: List<Pair<Double, Double>>,
    modifier: Modifier = Modifier,
    here: Double? = null,
    hereLabel: String? = null,
    color: Color = MaterialTheme.colorScheme.primary,
) {
    if (profile.size < 2) return
    val low = profile.minOf { it.second }
    val high = profile.maxOf { it.second }
    val done = LocalContentColor.current.copy(alpha = 0.35f)
    val labelText = MaterialTheme.colorScheme.onSurface
    val labelBack = MaterialTheme.colorScheme.surface
    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelMedium.merge(TextStyle(fontWeight = FontWeight.Bold, color = labelText))
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Column(Modifier.fillMaxWidth(0.18f).fillMaxHeight(), verticalArrangement = Arrangement.SpaceBetween) {
            Text(Format.meters(high), style = MaterialTheme.typography.labelMedium, maxLines = 1)
            Text(Format.meters(low), style = MaterialTheme.typography.labelMedium, maxLines = 1)
        }
        Canvas(Modifier.weight(1f).fillMaxHeight()) {
            val length = profile.last().first.coerceAtLeast(1.0)
            // At least 50 m of range, so a flat walk doesn't look like a mountain.
            val range = (high - low).coerceAtLeast(50.0)
            // With a marker, leave room above the line for its label.
            val top = if (here != null && hereLabel != null) 18.dp.toPx() else 0f
            val chart = size.height - top
            fun at(d: Double, e: Double) = Offset(
                (d / length * size.width).toFloat(),
                (top + chart - (e - low) / range * chart).toFloat(),
            )
            val outline = Path().apply {
                profile.forEachIndexed { i, (d, e) -> at(d, e).let { if (i == 0) moveTo(it.x, it.y) else lineTo(it.x, it.y) } }
            }
            val fill = Path().apply {
                addPath(outline)
                lineTo(size.width, size.height)
                lineTo(0f, size.height)
                close()
            }
            val split = here?.let { (it / length * size.width).toFloat().coerceIn(0f, size.width) }
            if (split == null) {
                drawPath(fill, color.copy(alpha = 0.2f))
                drawPath(outline, color, style = Stroke(2.dp.toPx()))
                return@Canvas
            }
            // Walked: grey. Ahead: the route colour.
            clipRect(right = split) {
                drawPath(fill, done.copy(alpha = 0.15f))
                drawPath(outline, done, style = Stroke(2.dp.toPx()))
            }
            clipRect(left = split) {
                drawPath(fill, color.copy(alpha = 0.25f))
                drawPath(outline, color, style = Stroke(2.5.dp.toPx()))
            }
            val dot = at(here, heightAt(profile, here))
            drawLine(color, Offset(split, top), Offset(split, size.height), strokeWidth = 2.dp.toPx())
            drawCircle(Color.White, radius = 6.dp.toPx(), center = dot)
            drawCircle(color, radius = 4.5.dp.toPx(), center = dot)
            if (hereLabel != null) {
                val text = measurer.measure(hereLabel, labelStyle)
                val pad = 4.dp.toPx()
                val w = text.size.width + 2 * pad
                // Centred on the marker, but kept inside the chart.
                val x = (split - w / 2).coerceIn(0f, (size.width - w).coerceAtLeast(0f))
                drawRoundRect(labelBack, Offset(x, 0f), Size(w, text.size.height.toFloat()), CornerRadius(pad))
                drawText(text, topLeft = Offset(x + pad, 0f))
            }
        }
    }
}

/** Height at [d] metres along, between the profile's points. */
private fun heightAt(profile: List<Pair<Double, Double>>, d: Double): Double {
    val i = profile.indexOfFirst { it.first >= d }
    if (i <= 0) return if (i == 0) profile.first().second else profile.last().second
    val (d0, e0) = profile[i - 1]
    val (d1, e1) = profile[i]
    return if (d1 == d0) e1 else e0 + (e1 - e0) * (d - d0) / (d1 - d0)
}
