package app.sentrek.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import app.sentrek.geo.route.ProfileMath
import kotlinx.coroutines.delay

/**
 * Elevation against distance ([profile]: metres along to metres high), scaled to fill the
 * box, with the highest and lowest heights on the left. Nothing for fewer than two points.
 *
 * With [here] (metres along), the stretch already walked is greyed out and a line and a dot
 * labelled [hereLabel] mark the position.
 *
 * Touching the profile (or sliding along it) shows the slope there, with height and distance;
 * it stays a few seconds after the finger lifts.
 *
 * [flags] (metres along): where the route's sections end, each a line with its number on top,
 * in orange as on the map.
 */
@Composable
fun ElevationProfile(
    profile: List<Pair<Double, Double>>,
    modifier: Modifier = Modifier,
    here: Double? = null,
    hereLabel: String? = null,
    color: Color = MaterialTheme.colorScheme.primary,
    flags: List<Double> = emptyList(),
) {
    if (profile.size < 2) return
    val low = profile.minOf { it.second }
    val high = profile.maxOf { it.second }
    val length = profile.last().first.coerceAtLeast(1.0)
    val done = LocalContentColor.current.copy(alpha = 0.35f)
    val ink = MaterialTheme.colorScheme.onSurface
    val paper = MaterialTheme.colorScheme.surface
    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelMedium.merge(TextStyle(fontWeight = FontWeight.Bold, color = ink))
    val flagStyle = MaterialTheme.typography.labelSmall.merge(TextStyle(fontWeight = FontWeight.Bold, color = Color.White))

    // Metres along where the finger is (or was, a moment ago).
    var touched by remember(profile) { mutableStateOf<Double?>(null) }
    var lifted by remember { mutableIntStateOf(0) }
    LaunchedEffect(lifted) {
        if (lifted == 0) return@LaunchedEffect
        delay(TOUCH_LINGER_MS)
        touched = null
    }

    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Column(Modifier.fillMaxWidth(0.18f).fillMaxHeight(), verticalArrangement = Arrangement.SpaceBetween) {
            Text(Format.elevation(high), style = MaterialTheme.typography.labelMedium, maxLines = 1)
            Text(Format.elevation(low), style = MaterialTheme.typography.labelMedium, maxLines = 1)
        }
        Canvas(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .pointerInput(profile) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        down.consume()
                        touched = (down.position.x / size.width).coerceIn(0f, 1f) * length
                        while (true) {
                            val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            change.consume()
                            touched = (change.position.x / size.width).coerceIn(0f, 1f) * length
                        }
                        lifted++
                    }
                },
        ) {
            // At least 50 m of range, so a flat walk doesn't look like a mountain.
            val range = (high - low).coerceAtLeast(50.0)
            // With a marker, leave room above the line for its label.
            val top = if ((here != null && hereLabel != null) || flags.isNotEmpty()) 18.dp.toPx() else 0f
            val chart = size.height - top
            fun x(d: Double) = (d / length * size.width).toFloat().coerceIn(0f, size.width)
            fun at(d: Double, e: Double) = Offset(x(d), (top + chart - (e - low) / range * chart).toFloat())
            val outline = Path().apply {
                profile.forEachIndexed { i, (d, e) -> at(d, e).let { if (i == 0) moveTo(it.x, it.y) else lineTo(it.x, it.y) } }
            }
            val fill = Path().apply {
                addPath(outline)
                lineTo(size.width, size.height)
                lineTo(0f, size.height)
                close()
            }
            if (here == null) {
                drawPath(fill, color.copy(alpha = 0.2f))
                drawPath(outline, color, style = Stroke(2.dp.toPx()))
            } else {
                // Walked: grey. Ahead: the route colour.
                val split = x(here)
                clipRect(right = split) {
                    drawPath(fill, done.copy(alpha = 0.15f))
                    drawPath(outline, done, style = Stroke(2.dp.toPx()))
                }
                clipRect(left = split) {
                    drawPath(fill, color.copy(alpha = 0.25f))
                    drawPath(outline, color, style = Stroke(2.5.dp.toPx()))
                }
                drawLine(color, Offset(split, top), Offset(split, size.height), strokeWidth = 2.dp.toPx())
                dot(at(here, ProfileMath.heightAt(profile, here)), color)
            }

            flags.forEachIndexed { i, d ->
                val fx = x(d)
                val r = 8.dp.toPx()
                drawLine(SECTION_FLAG, Offset(fx, r), Offset(fx, size.height), strokeWidth = 1.5.dp.toPx())
                val cx = fx.coerceIn(r, size.width - r)
                drawCircle(SECTION_FLAG, radius = r, center = Offset(cx, r))
                val number = measurer.measure("${i + 1}", flagStyle)
                drawText(number, topLeft = Offset(cx - number.size.width / 2f, r - number.size.height / 2f))
            }

            val t = touched
            if (t != null) {
                // Dashed, so it doesn't look like the "you are here" line.
                val tx = x(t)
                drawLine(
                    ink, Offset(tx, 0f), Offset(tx, size.height),
                    strokeWidth = 1.5.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx())),
                )
                dot(at(t, ProfileMath.heightAt(profile, t)), ink)
                val text = Format.slope(ProfileMath.slopeDegrees(profile, t)) + " · " +
                    Format.elevation(ProfileMath.heightAt(profile, t)) + " · " + Format.distance(t)
                label(measurer, text, labelStyle, tx, paper)
            } else if (here != null && hereLabel != null) {
                label(measurer, hereLabel, labelStyle, x(here), paper)
            }
        }
    }
}

private fun DrawScope.dot(center: Offset, color: Color) {
    drawCircle(Color.White, radius = 6.dp.toPx(), center = center)
    drawCircle(color, radius = 4.5.dp.toPx(), center = center)
}

/** A label on a rounded background along the top, centred on [x] but kept inside. */
private fun DrawScope.label(measurer: TextMeasurer, text: String, style: TextStyle, x: Float, background: Color) {
    val measured = measurer.measure(text, style)
    val pad = 4.dp.toPx()
    val w = measured.size.width + 2 * pad
    val left = (x - w / 2).coerceIn(0f, (size.width - w).coerceAtLeast(0f))
    drawRoundRect(background, Offset(left, 0f), Size(w, measured.size.height.toFloat()), CornerRadius(pad))
    drawText(measured, topLeft = Offset(left + pad, 0f))
}

private const val TOUCH_LINGER_MS = 3_000L

/** The section ends' orange, as on the map (PlanLayers). */
private val SECTION_FLAG = Color(0xFFEF6C00)
