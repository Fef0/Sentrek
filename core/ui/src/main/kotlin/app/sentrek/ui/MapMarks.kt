package app.sentrek.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.sentrek.geo.units.UnitFormat
import kotlinx.coroutines.delay
import kotlin.math.max

/**
 * Scale bar with metric above the line and imperial below, both measured from the left tick:
 * the longer tick up marks the metric length, the one down the imperial length. Black with a
 * white outline ([night]: the other way round), readable over any map.
 */
@Composable
fun ScaleBar(metersPerPx: Double, modifier: Modifier = Modifier, night: Boolean = false) {
    if (!(metersPerPx > 0) || metersPerPx.isInfinite()) return
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val ink = if (night) Color.White else Color.Black
    val halo = if (night) Color.Black else Color.White
    val style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Bold, color = ink)

    val maxMeters = with(density) { SCALE_MAX_WIDTH.toPx() } * metersPerPx
    val metric = UnitFormat.roundDown(maxMeters)
    val metricLabel = if (metric >= 1_000) "${number(metric / 1_000)} km" else "${number(metric)} m"
    val maxFeet = maxMeters / UnitFormat.FOOT_M
    val (imperial, imperialLabel) = if (maxFeet < FEET_PER_MILE) {
        val feet = UnitFormat.roundDown(maxFeet)
        feet * UnitFormat.FOOT_M to "${number(feet)} ft"
    } else {
        val miles = UnitFormat.roundDown(maxMeters / UnitFormat.MILE_M)
        miles * UnitFormat.MILE_M to "${number(miles)} mi"
    }

    val metricText = measurer.measure(metricLabel, style)
    val imperialText = measurer.measure(imperialLabel, style)
    val metricPx = (metric / metersPerPx).toFloat()
    val imperialPx = (imperial / metersPerPx).toFloat()
    with(density) {
        val line = 2.dp.toPx()
        val outline = 4.dp.toPx()
        val gap = 3.dp.toPx()
        val tick = metricText.size.height + gap
        val width = max(max(metricPx, imperialPx), max(metricText.size.width, imperialText.size.width) + gap) + outline
        val height = 2 * tick + outline
        Canvas(modifier.size(width.toDp(), height.toDp())) {
            val x0 = outline / 2
            val y = tick + outline / 2
            for ((color, w) in listOf(halo to outline, ink to line)) {
                drawLine(color, Offset(x0, y), Offset(x0 + max(metricPx, imperialPx), y), w, StrokeCap.Square)
                drawLine(color, Offset(x0, y - tick + outline / 2), Offset(x0, y + tick - outline / 2), w, StrokeCap.Square)
                drawLine(color, Offset(x0 + metricPx, y - tick + outline / 2), Offset(x0 + metricPx, y), w, StrokeCap.Square)
                drawLine(color, Offset(x0 + imperialPx, y), Offset(x0 + imperialPx, y + tick - outline / 2), w, StrokeCap.Square)
            }
            haloText(metricText, Offset(x0 + gap, y - gap - metricText.size.height), halo)
            haloText(imperialText, Offset(x0 + gap, y + gap), halo)
        }
    }
}

private fun DrawScope.haloText(text: TextLayoutResult, at: Offset, halo: Color) {
    drawText(text, color = halo, topLeft = at, drawStyle = Stroke(3.dp.toPx()))
    // Fill named explicitly: the stroke style would otherwise stick to the text.
    drawText(text, topLeft = at, drawStyle = Fill)
}

/** 2, 0.5, 200: no ".0" on whole numbers. */
private fun number(value: Double): String =
    if (value >= 1 && value == Math.rint(value)) value.toLong().toString() else value.toBigDecimal().stripTrailingZeros().toPlainString()

/**
 * The map data credit, shown when the map opens and faded out after a few seconds, as the
 * OpenStreetMap attribution guidelines allow. The full credits stay in Settings > About.
 */
@Composable
fun MapCredit(modifier: Modifier = Modifier) {
    var shown by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        delay(CREDIT_MS)
        shown = false
    }
    AnimatedVisibility(shown, modifier, exit = fadeOut()) {
        Text(
            MAP_CREDIT,
            style = MaterialTheme.typography.labelSmall,
            color = Color(0xFF333333),
            modifier = Modifier
                .background(Color.White.copy(alpha = 0.75f), RoundedCornerShape(4.dp))
                .padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

private val SCALE_MAX_WIDTH = 110.dp
private const val FEET_PER_MILE = 5_280.0
private const val CREDIT_MS = 5_000L
private const val MAP_CREDIT = "© OpenFreeMap © OpenMapTiles © OpenStreetMap contributors"
