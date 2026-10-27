package app.sentrek.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/**
 * Elevation against distance ([profile]: metres along to metres high), scaled to fill the
 * box, with the highest and lowest heights on the left. Nothing for fewer than two points.
 */
@Composable
fun ElevationProfile(profile: List<Pair<Double, Double>>, modifier: Modifier = Modifier) {
    if (profile.size < 2) return
    val low = profile.minOf { it.second }
    val high = profile.maxOf { it.second }
    val line = MaterialTheme.colorScheme.primary
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Column(Modifier.fillMaxWidth(0.18f).fillMaxHeight(), verticalArrangement = Arrangement.SpaceBetween) {
            Text(Format.meters(high), style = MaterialTheme.typography.labelMedium, maxLines = 1)
            Text(Format.meters(low), style = MaterialTheme.typography.labelMedium, maxLines = 1)
        }
        Canvas(Modifier.weight(1f).fillMaxHeight()) {
            val length = profile.last().first.coerceAtLeast(1.0)
            // At least 50 m of range, so a flat walk doesn't look like a mountain.
            val range = (high - low).coerceAtLeast(50.0)
            fun at(d: Double, e: Double) = Offset(
                (d / length * size.width).toFloat(),
                (size.height - (e - low) / range * size.height).toFloat(),
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
            drawPath(fill, line.copy(alpha = 0.2f))
            drawPath(outline, line, style = Stroke(2.dp.toPx()))
        }
    }
}
