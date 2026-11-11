package app.sentrek.record

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import app.sentrek.data.STATS_ALL_TIME
import app.sentrek.data.STATS_THIS_YEAR
import app.sentrek.feature.record.R
import app.sentrek.geo.track.PersonalPace
import app.sentrek.geo.track.Trip
import app.sentrek.geo.track.TripPeriods
import app.sentrek.geo.track.TripRecords
import app.sentrek.geo.track.TripTotals
import app.sentrek.ui.Format
import app.sentrek.ui.SentrekIcons
import app.sentrek.ui.TouchTarget
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/** The user's recorded walks as the stats see them. */
fun List<SavedTrack>.trips(): List<Trip> = map { Trip(it.startTime, it.distance, it.ascent, it.duration, it.descent, it.id, it.name) }

/**
 * The two chart colours: this year (the app's orange) and the year before (blue), stepped
 * for light or dark surfaces. Checked for colour blindness and contrast on both.
 */
private class ChartColors(val now: Color, val before: Color)

@Composable
private fun chartColors(): ChartColors =
    if (MaterialTheme.colorScheme.surface.luminance() < 0.5f) {
        ChartColors(Color(0xFFE0661F), Color(0xFF3F86D9))
    } else {
        ChartColors(Color(0xFFE85D04), Color(0xFF2A78D6))
    }

/**
 * What the user has walked: folded, one line (distance, walks, climb, a bar per month) that a
 * tap opens. Open: any year or all time, tiles (moving time, longest, most climb, average
 * speed, climb per hour, and against signpost times), the year's distance laid on the year
 * before, and the speed of each walk. Only recorded walks count, never plans. Nothing at all
 * until there is a walk.
 */
@Composable
fun TripTotalsCard(
    walks: List<SavedTrack>,
    /** STATS_THIS_YEAR, STATS_ALL_TIME, or a year. */
    year: Int,
    onYear: (Int) -> Unit,
    open: Boolean,
    onOpen: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val trips = remember(walks) { walks.trips() }
    if (TripTotals.of(trips).hikes == 0) return
    val thisYear = remember { Calendar.getInstance().get(Calendar.YEAR) }
    // The years with walks, newest first, this one always: the chips to pick from.
    val years = remember(trips) {
        (TripPeriods.byYear(trips, thisYear).filter { it.second > 0 }.map { it.first } + thisYear).distinct().sortedDescending()
    }
    val allTime = year == STATS_ALL_TIME
    // A year with no walks left (all deleted) falls back to this one.
    val shown = when {
        allTime -> thisYear
        year == STATS_THIS_YEAR || year !in years -> thisYear
        else -> year
    }
    val since = if (allTime) Long.MIN_VALUE else yearStart(shown)
    val until = if (allTime) Long.MAX_VALUE else yearStart(shown + 1)
    val totals = remember(trips, allTime, shown) { TripTotals.of(trips, since, until) }
    val records = remember(trips, allTime, shown) { TripRecords.of(trips, since, until) }
    val inPeriod = remember(trips, allTime, shown) { trips.filter { it.start in since until until } }
    val colors = chartColors()
    val muted = MaterialTheme.colorScheme.onSurfaceVariant

    Surface(modifier = modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            // Folded or open, this line stays: the numbers that matter most, at a glance.
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = TouchTarget)
                    .clickable(onClickLabel = stringResource(if (open) R.string.stats_fold else R.string.stats_open)) { onOpen(!open) },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        when {
                            allTime -> stringResource(R.string.totals_all_time)
                            shown == thisYear -> stringResource(R.string.totals_this_year)
                            else -> shown.toString()
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = muted,
                    )
                    if (totals.hikes == 0) {
                        Text(stringResource(R.string.totals_none), style = MaterialTheme.typography.titleMedium)
                    } else {
                        Text(
                            buildAnnotatedString {
                                withStyle(SpanStyle(fontWeight = FontWeight.Bold, fontSize = MaterialTheme.typography.headlineSmall.fontSize)) {
                                    append(Format.distance(totals.distance))
                                }
                                append("  ")
                                append(pluralStringResource(R.plurals.totals_hikes, totals.hikes, totals.hikes))
                                append(" · ↑ ")
                                append(Format.elevation(totals.ascent))
                            },
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                        )
                    }
                }
                val spark = remember(trips, allTime, shown) {
                    if (allTime) TripPeriods.byYear(trips, thisYear).takeLast(MAX_YEARS).map { it.second } else TripPeriods.byMonth(trips, shown).toList()
                }
                if (spark.size > 1) MiniBars(spark, colors.now, Modifier.width(64.dp).height(28.dp))
                Icon(
                    if (open) SentrekIcons.ExpandLess else SentrekIcons.ExpandMore,
                    contentDescription = null,
                    tint = muted,
                )
            }

            AnimatedVisibility(open, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                Column(Modifier.padding(top = 4.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    // Every year with walks, and all of them: scrolls sideways when there are many.
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val chips = listOf(STATS_ALL_TIME to stringResource(R.string.totals_all_time)) + years.map { y -> (if (y == thisYear) STATS_THIS_YEAR else y) to y.toString() }
                        for ((value, label) in chips) {
                            val selected = if (value == STATS_THIS_YEAR) !allTime && shown == thisYear else if (value == STATS_ALL_TIME) allTime else !allTime && shown == value
                            FilterChip(
                                selected = selected,
                                onClick = { onYear(value) },
                                label = { Text(label, style = MaterialTheme.typography.titleSmall) },
                                leadingIcon = if (selected) {
                                    { Icon(SentrekIcons.Check, contentDescription = null, modifier = Modifier.size(18.dp)) }
                                } else {
                                    null
                                },
                                modifier = Modifier.heightIn(min = TouchTarget),
                            )
                        }
                    }
                    Tiles(totals, records, inPeriod)
                    if (allTime) {
                        val byYear = remember(trips) { TripPeriods.byYear(trips, thisYear).takeLast(MAX_YEARS) }
                        if (byYear.size > 1) {
                            DistanceBars(
                                labels = byYear.map { "’" + (it.first % 100).toString().padStart(2, '0') },
                                values = byYear.map { it.second },
                                names = byYear.map { it.first.toString() },
                                caption = stringResource(R.string.totals_per_year),
                                color = colors.now,
                            )
                        }
                    } else {
                        YearRace(trips, shown, endDay = if (shown == thisYear) today() else DAYS - 1, colors)
                    }
                    SpeedDots(inPeriod, colors.now)
                }
            }
        }
    }
}

/**
 * Small tiles under the line: what it has no room for. Speed and climb per hour are plain
 * averages of the time spent moving; against signpost times is apart, once there are
 * enough walks to tell.
 */
@Composable
private fun Tiles(totals: TripTotals, records: TripRecords, walks: List<Trip>) {
    val hours = totals.movingMillis / 3_600_000.0
    val signposts = remember(walks) { PersonalPace.factor(walks) }
    val tiles = buildList {
        add(stringResource(R.string.stats_moving) to hours(totals.movingMillis))
        if (hours > 0) add(stringResource(R.string.stats_speed) to Format.speed(totals.distance / (totals.movingMillis / 1000.0)))
        records.longest?.let { add(stringResource(R.string.stats_longest) to Format.distance(it.distance)) }
        if (hours > 0) add(stringResource(R.string.stats_climb_rate) to "↑ " + Format.elevation(totals.ascent / hours))
        records.mostClimb?.let { add(stringResource(R.string.stats_most_climb) to "↑ " + Format.elevation(it.ascent)) }
        signposts?.let { add(stringResource(R.string.stats_vs_signposts) to paceWords(it)) }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        tiles.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { (label, value) ->
                    Surface(Modifier.weight(1f), shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surface) {
                        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1)
                        }
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

/** "12% faster", "8% slower", or signpost times. */
@Composable
private fun paceWords(factor: Double): String {
    val percent = ((1 - factor) * 100).roundToInt()
    return when {
        abs(percent) < SAME_PACE_PERCENT -> stringResource(R.string.stats_pace_same)
        percent > 0 -> stringResource(R.string.stats_pace_faster, percent)
        else -> stringResource(R.string.stats_pace_slower, -percent)
    }
}

/**
 * A year's distance, day by day, on the year before's: the running totals as two lines, the
 * year filled under. Ahead or behind is seen at once. A finger on it reads both on that day.
 * [endDay]: where the year has got to (today, or its last day for a past year).
 */
@Composable
private fun YearRace(trips: List<Trip>, year: Int, endDay: Int, colors: ChartColors) {
    // Keyed on the year too: picking another must draw that one.
    val now = remember(trips, year) { TripPeriods.cumulative(trips, year) }
    val before = remember(trips, year) { TripPeriods.cumulative(trips, year - 1) }
    val hasBefore = before.size > 1
    val today = endDay
    var day by remember(year) { mutableStateOf<Int?>(null) }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val grid = MaterialTheme.colorScheme.outlineVariant
    val ring = MaterialTheme.colorScheme.surfaceVariant
    val measurer = rememberTextMeasurer()
    val small = MaterialTheme.typography.labelSmall.copy(color = muted)
    val label = MaterialTheme.typography.labelMedium.copy(color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold)
    val nowTotal = now.last().second
    val beforeTotal = before.last().second
    val top = niceTop(max(nowTotal, beforeTotal))

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val picked = day
        Text(
            if (picked == null) {
                if (hasBefore) stringResource(R.string.stats_race, year, year - 1) else stringResource(R.string.stats_so_far, year)
            } else {
                buildString {
                    append(dayName(year, picked))
                    append(" · ").append(year).append(": ").append(Format.distance(valueAt(now, picked)))
                    if (hasBefore) append(" · ").append(year - 1).append(": ").append(Format.distance(valueAt(before, picked)))
                }
            },
            style = MaterialTheme.typography.labelLarge,
            color = if (picked == null) muted else MaterialTheme.colorScheme.onSurface,
        )
        if (hasBefore) {
            // Line style as well as colour says which is which.
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                LegendLine(colors.now, dashed = false, text = "$year")
                LegendLine(colors.before, dashed = true, text = "${year - 1} · ${Format.distance(beforeTotal)}")
            }
        }
        val spoken = buildString {
            append(year).append(": ").append(Format.distance(nowTotal))
            if (hasBefore) append(". ").append(year - 1).append(": ").append(Format.distance(beforeTotal))
        }
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(132.dp)
                .semantics { contentDescription = spoken }
                .pointerInput(year) {
                    val toDay = { x: Float -> ((x / size.width) * DAYS).toInt().coerceIn(0, DAYS - 1) }
                    detectTapGestures { day = if (day == null) toDay(it.x) else null }
                }
                .pointerInput(year) {
                    val toDay = { x: Float -> ((x / size.width) * DAYS).toInt().coerceIn(0, DAYS - 1) }
                    detectDragGestures(onDragEnd = {}, onDragCancel = {}) { change, _ -> day = toDay(change.position.x) }
                },
        ) {
            val labelSpace = 14.dp.toPx()
            val h = size.height - labelSpace
            val x = { d: Int -> d / DAYS.toFloat() * size.width }
            val y = { v: Double -> (h - (v / top * (h - 8.dp.toPx()))).toFloat() }
            // A faint line at the top value, named: the scale without an axis.
            val dash = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx()))
            drawLine(grid, Offset(0f, y(top)), Offset(size.width, y(top)), 1.dp.toPx(), pathEffect = dash)
            drawText(measurer, Format.distance(top), Offset(0f, y(top) + 2.dp.toPx()), small)
            drawLine(grid, Offset(0f, h), Offset(size.width, h), 1.dp.toPx())
            // Months along the bottom.
            val months = monthNames("MMMMM")
            months.forEachIndexed { i, m ->
                val cx = (i + 0.5f) / 12 * size.width
                val text = measurer.measure(m, small)
                drawText(text, topLeft = Offset(cx - text.size.width / 2, h + 2.dp.toPx()))
            }
            if (hasBefore) {
                drawPath(steps(before, DAYS - 1, x, y), colors.before, style = Stroke(2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx())), join = StrokeJoin.Round))
            }
            val line = steps(now, today, x, y)
            val fill = Path().apply {
                addPath(line)
                lineTo(x(today), h)
                lineTo(0f, h)
                close()
            }
            drawPath(fill, Brush.verticalGradient(listOf(colors.now.copy(alpha = 0.28f), colors.now.copy(alpha = 0.02f)), startY = y(top), endY = h))
            drawPath(line, colors.now, style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            // Today: where this year has got to, its total beside it.
            val end = Offset(x(today), y(nowTotal))
            dot(end, colors.now, ring)
            val total = measurer.measure(Format.distance(nowTotal), label)
            val tx = (end.x - total.size.width - 8.dp.toPx()).coerceAtLeast(0f)
            val ty = (end.y - total.size.height - 6.dp.toPx()).coerceAtLeast(0f)
            if (day == null) drawText(total, topLeft = Offset(tx, ty))
            // The finger's day: a line and both values on it.
            day?.let { d ->
                drawLine(muted, Offset(x(d), 0f), Offset(x(d), h), 1.dp.toPx())
                if (d <= today) dot(Offset(x(d), y(valueAt(now, d))), colors.now, ring)
                if (hasBefore) dot(Offset(x(d), y(valueAt(before, d))), colors.before, ring)
            }
        }
    }
}

/**
 * The speed of each walk while moving, one dot per walk in order, around a dashed line at
 * the average. Steeper walks sit lower: a tap names the walk with its climb per hour too.
 */
@Composable
private fun SpeedDots(trips: List<Trip>, color: Color) {
    val walks = remember(trips) { PersonalPace.usableWalks(trips) }
    if (walks.size < 2) return
    var picked by remember(walks) { mutableStateOf<Int?>(null) }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val grid = MaterialTheme.colorScheme.outlineVariant
    val ring = MaterialTheme.colorScheme.surfaceVariant
    val measurer = rememberTextMeasurer()
    val small = MaterialTheme.typography.labelSmall.copy(color = muted)
    val speeds = walks.map { (it.distance / (it.movingMillis / 1000.0)).toFloat() }
    val average = (walks.sumOf { it.distance } / (walks.sumOf { it.movingMillis } / 1000.0)).toFloat()
    // Fitted to the dots, with the average in: a few km/h either way fills the chart.
    val hi = max(speeds.max(), average)
    val lo = minOf(speeds.min(), average)
    val span = max(hi - lo, 0.4f) * 1.3f
    val top = (hi + lo) / 2 + span / 2
    val averageText = stringResource(R.string.stats_average, Format.speed(average.toDouble()))
    val dateFormat = remember { SimpleDateFormat("d MMM yyyy", Locale.getDefault()) }
    val climbPerHour = stringResource(R.string.stats_climb_rate)

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val i = picked
        Text(
            if (i == null) {
                stringResource(R.string.stats_speed_chart)
            } else {
                val w = walks[i]
                val rate = w.ascent / (w.movingMillis / 3_600_000.0)
                "${w.name} · ${dateFormat.format(w.start)} · ${Format.speed(speeds[i].toDouble())} · $climbPerHour ↑ ${Format.elevation(rate)}"
            },
            style = MaterialTheme.typography.labelLarge,
            color = if (i == null) muted else MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
        )
        val spoken = walks.indices.joinToString(", ") { "${walks[it].name} ${Format.speed(speeds[it].toDouble())}" }
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(96.dp)
                .semantics { contentDescription = "$averageText. $spoken" }
                .pointerInput(walks) {
                    detectTapGestures { offset ->
                        val step = size.width / walks.size.toFloat()
                        val n = (offset.x / step).toInt().coerceIn(0, walks.lastIndex)
                        picked = if (picked == n) null else n
                    }
                },
        ) {
            val step = size.width / walks.size
            val pad = 14.dp.toPx()
            val y = { v: Float -> pad + (top - v) / span * (size.height - 2 * pad) }
            val mid = y(average)
            drawLine(grid, Offset(0f, mid), Offset(size.width, mid), 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx())))
            val label = measurer.measure(averageText, small)
            drawText(label, topLeft = Offset(size.width - label.size.width, (mid + 2.dp.toPx()).coerceAtMost(size.height - label.size.height)))
            // A thin line through the dots: their order in time, so a trend reads as one.
            val path = Path()
            speeds.forEachIndexed { n, v ->
                val o = Offset(step * (n + 0.5f), y(v))
                if (n == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y)
            }
            drawPath(path, color.copy(alpha = 0.35f), style = Stroke(1.5.dp.toPx(), join = StrokeJoin.Round))
            speeds.forEachIndexed { n, v ->
                dot(Offset(step * (n + 0.5f), y(v)), color, ring, big = picked == n)
            }
        }
    }
}

/** A dot ringed in the card's colour, so it stands off the line under it. */
private fun DrawScope.dot(center: Offset, color: Color, ring: Color, big: Boolean = false) {
    val r = (if (big) 7 else 5).dp.toPx()
    drawCircle(ring, r + 2.dp.toPx(), center)
    drawCircle(color, r, center)
}

/** The running total as steps: flat between walks, straight up on a walk's day. */
private fun steps(points: List<Pair<Int, Double>>, endDay: Int, x: (Int) -> Float, y: (Double) -> Float): Path = Path().apply {
    moveTo(x(0), y(0.0))
    var last = 0.0
    for ((d, v) in points) {
        if (d > endDay) break
        lineTo(x(d), y(last))
        lineTo(x(d), y(v))
        last = v
    }
    lineTo(x(endDay), y(last))
}

private fun valueAt(points: List<Pair<Int, Double>>, day: Int): Double = points.lastOrNull { it.first <= day }?.second ?: 0.0

/** A round number above [v] for the top of the chart: 10, 20, 50, 100… km. */
private fun niceTop(v: Double): Double {
    if (v <= 0) return 10_000.0
    var step = 1_000.0
    while (true) {
        for (m in doubleArrayOf(1.0, 2.0, 5.0)) if (step * m >= v * 1.05) return step * m
        step *= 10
    }
}

@Composable
private fun LegendLine(color: Color, dashed: Boolean, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Canvas(Modifier.size(width = 18.dp, height = 8.dp)) {
            drawLine(
                color, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 2.5.dp.toPx(),
                pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx())) else null,
            )
        }
        Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Tiny bars in the folded line: the shape of the year (or the years), no labels. */
@Composable
private fun MiniBars(values: List<Double>, color: Color, modifier: Modifier) {
    val max = values.maxOrNull()?.takeIf { it > 0 } ?: 1.0
    val faint = MaterialTheme.colorScheme.outlineVariant
    Canvas(modifier) {
        val slot = size.width / values.size
        val w = slot * 0.6f
        values.forEachIndexed { i, v ->
            val left = i * slot + (slot - w) / 2
            if (v <= 0) {
                drawRect(faint, Offset(left, size.height - 1.dp.toPx()), Size(w, 1.dp.toPx()))
            } else {
                val h = (v / max * size.height).toFloat().coerceAtLeast(2.dp.toPx())
                drawRoundRect(color, Offset(left, size.height - h), Size(w, h), CornerRadius(1.5.dp.toPx()))
            }
        }
    }
}

/**
 * Distance walked per year: one bar each, on a baseline. A tap on a bar names it ("2025:
 * 420 km") in the caption line; without one, the caption says what the bars are.
 */
@Composable
private fun DistanceBars(labels: List<String>, values: List<Double>, names: List<String>, caption: String, color: Color) {
    var selected by remember(values) { mutableStateOf<Int?>(null) }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val baseline = MaterialTheme.colorScheme.outlineVariant
    val max = values.maxOrNull()?.takeIf { it > 0 } ?: 1.0
    val spoken = names.zip(values).joinToString(", ") { (n, v) -> "$n ${Format.distance(v)}" }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            selected?.let { "${names[it]}: ${Format.distance(values[it])}" } ?: caption,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected != null) MaterialTheme.colorScheme.onSurface else muted,
        )
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(72.dp)
                .semantics { contentDescription = "$caption: $spoken" }
                .pointerInput(values) {
                    detectTapGestures { offset ->
                        val i = (offset.x / (size.width.toFloat() / values.size)).toInt().coerceIn(0, values.lastIndex)
                        selected = if (selected == i) null else i
                    }
                },
        ) {
            val slot = size.width / values.size
            // Never wider than a finger: two years alone would be two slabs.
            val width = (slot - 2.dp.toPx().coerceAtLeast(slot * 0.25f)).coerceAtMost(MAX_BAR_DP.dp.toPx())
            val radius = 4.dp.toPx().coerceAtMost(width / 2)
            val bottom = size.height - 1.dp.toPx()
            values.forEachIndexed { i, v ->
                if (v <= 0) return@forEachIndexed
                val h = ((v / max) * (bottom - radius)).toFloat().coerceAtLeast(radius * 2)
                val left = i * slot + (slot - width) / 2
                val alpha = if (selected == null || selected == i) 1f else 0.4f
                // One shape, rounded at the top and square on the baseline.
                val bar = Path().apply {
                    addRoundRect(
                        RoundRect(
                            Rect(left, bottom - h, left + width, bottom),
                            topLeft = CornerRadius(radius), topRight = CornerRadius(radius),
                            bottomLeft = CornerRadius.Zero, bottomRight = CornerRadius.Zero,
                        ),
                    )
                }
                drawPath(bar, color.copy(alpha = alpha))
            }
            drawLine(baseline, Offset(0f, bottom + 0.5.dp.toPx()), Offset(size.width, bottom + 0.5.dp.toPx()), 1.dp.toPx())
        }
        Row(Modifier.fillMaxWidth()) {
            labels.forEachIndexed { i, label ->
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Text(
                        label,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (selected == i) MaterialTheme.colorScheme.onSurface else muted,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/** The twelve month names in [pattern] ("MMMMM": J F M…), in the user's language. */
private fun monthNames(pattern: String): List<String> {
    val format = SimpleDateFormat(pattern, Locale.getDefault())
    val calendar = Calendar.getInstance().apply { set(Calendar.DAY_OF_MONTH, 1) }
    return (0 until 12).map { calendar.set(Calendar.MONTH, it); format.format(calendar.time) }
}

/** "12 Jun" for [day] (from 0) of [year]. */
private fun dayName(year: Int, day: Int): String {
    val calendar = Calendar.getInstance().apply {
        set(Calendar.YEAR, year)
        set(Calendar.DAY_OF_YEAR, day + 1)
    }
    return SimpleDateFormat("d MMM", Locale.getDefault()).format(calendar.time)
}

/** Moving time to the minute: "62:10 h". */
private fun hours(millis: Long): String {
    val minutes = millis / 60_000
    return String.format(Locale.ROOT, "%d:%02d h", minutes / 60, minutes % 60)
}

/** Today, as a day of the year from 0. */
private fun today(): Int = Calendar.getInstance().get(Calendar.DAY_OF_YEAR) - 1

/** Midnight on 1 January of [year], here. */
private fun yearStart(year: Int): Long = Calendar.getInstance().apply {
    set(Calendar.YEAR, year)
    set(Calendar.DAY_OF_YEAR, 1)
    set(Calendar.HOUR_OF_DAY, 0)
    set(Calendar.MINUTE, 0)
    set(Calendar.SECOND, 0)
    set(Calendar.MILLISECOND, 0)
}.timeInMillis

private const val DAYS = 365

/** Within this many percent, the user walks at signpost times. */
private const val SAME_PACE_PERCENT = 3

/** The widest a bar gets, in dp. */
private const val MAX_BAR_DP = 28

/** Bars all time: the last this many years, so each stays wide enough to tap. */
private const val MAX_YEARS = 12
