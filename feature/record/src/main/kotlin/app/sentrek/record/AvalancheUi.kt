package app.sentrek.record

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import app.sentrek.feature.record.R
import app.sentrek.geo.avalanche.Bulletin
import app.sentrek.ui.Format
import app.sentrek.ui.TouchTarget
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The avalanche bulletin in force for the place: the danger level as the services draw it
 * (the EAWS colours and numbers), by height and part of the day when it changes, the
 * avalanche problems with the slopes they're on, and the next day's level once it is out.
 */
@Composable
fun AvalancheCard(avalanche: Avalanche) {
    val b = avalanche.now
    var open by remember { mutableStateOf(false) }
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                DangerBadge(b.maxLevel, 52.dp)
                Column {
                    Text(
                        stringResource(R.string.avalanche_title),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "${b.maxLevel} · ${stringResource(LEVEL_NAMES[b.maxLevel - 1])}",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
            // One line per height band and part of the day, when the danger isn't the same everywhere.
            val ratings = b.ratings.distinct().sortedWith(
                compareBy({ it.period.ordinal }, { it.band?.ordinal ?: if (it.elevation.lower != null) 0 else 1 }),
            )
            if (ratings.map { it.level }.distinct().size > 1) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (r in ratings) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            DangerBadge(r.level, 24.dp)
                            Text(
                                listOfNotNull(period(r.period), r.band?.let { band(it) } ?: heights(r.elevation)).joinToString(", ") +
                                    " · " + stringResource(LEVEL_NAMES[r.level - 1]),
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        }
                    }
                }
            }
            for (p in b.problems.distinctBy { it.type to it.aspects to it.elevation }) ProblemRow(p)
            b.highlights?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            NextDay(avalanche)
            Text(
                listOfNotNull(
                    b.provider?.let { stringResource(R.string.avalanche_by, it) },
                    b.validUntil.takeIf { it > 0 }?.let { stringResource(R.string.avalanche_until, dayTime(it)) },
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (b.activity != null || b.snowpack != null || b.outlook != null || b.problems.any { it.comment != null } || b.website != null) {
                OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth().heightIn(min = TouchTarget)) {
                    Text(stringResource(R.string.avalanche_full))
                }
            }
        }
    }
    if (open) FullBulletin(b) { open = false }
}

@Composable
private fun NextDay(avalanche: Avalanche) {
    val next = avalanche.next
    if (next != null && next.maxLevel > 0) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.avalanche_tomorrow), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
            DangerBadge(next.maxLevel, 24.dp)
            Text(stringResource(LEVEL_NAMES[next.maxLevel - 1]), style = MaterialTheme.typography.bodyLarge)
        }
        return
    }
    val tendency = when (avalanche.now.tendency) {
        "increasing" -> R.string.avalanche_tendency_increasing
        "steady" -> R.string.avalanche_tendency_steady
        "decreasing" -> R.string.avalanche_tendency_decreasing
        else -> return
    }
    Text(
        stringResource(R.string.avalanche_tomorrow) + " " + stringResource(tendency),
        style = MaterialTheme.typography.bodyLarge,
    )
}

@Composable
private fun ProblemRow(p: Bulletin.Problem) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        AspectRose(p.aspects, 40.dp)
        Column(Modifier.weight(1f)) {
            Text(
                PROBLEM_NAMES[p.type]?.let { stringResource(it) } ?: p.type.replace('_', ' '),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Bold,
            )
            val details = listOfNotNull(
                p.aspects.takeIf { it.isNotEmpty() && it.size < Bulletin.ASPECTS.size }?.joinToString(" "),
                p.aspects.takeIf { it.size == Bulletin.ASPECTS.size }?.let { stringResource(R.string.avalanche_all_aspects) },
                heights(p.elevation) ?: p.bands.takeIf { it.isNotEmpty() }?.map { band(it) }?.joinToString(", "),
                period(p.period),
            )
            if (details.isNotEmpty()) {
                Text(details.joinToString(" · "), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** The danger level in the colours of the European danger scale, with its number. */
@Composable
fun DangerBadge(level: Int, size: Dp) {
    val label = if (level in 1..5) stringResource(LEVEL_NAMES[level - 1]) else ""
    Box(
        Modifier.size(size).clip(RoundedCornerShape(size / 5)).semantics { contentDescription = "$level $label" },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(size)) {
            if (level >= 5) {
                // Very high: red and black squares.
                val n = 4
                val cell = this.size.width / n
                for (i in 0 until n) for (j in 0 until n) {
                    drawRect(if ((i + j) % 2 == 0) LEVEL_COLORS[3] else Color.Black, Offset(i * cell, j * cell), Size(cell, cell))
                }
            } else {
                drawRect(LEVEL_COLORS[(level - 1).coerceIn(0, 3)])
            }
        }
        Text(
            level.toString(),
            style = TextStyle(
                fontSize = (size.value * 0.5f).sp,
                fontWeight = FontWeight.Black,
                color = if (level >= 5) Color.White else Color.Black,
                shadow = if (level >= 5) Shadow(Color.Black, blurRadius = 6f) else null,
            ),
        )
    }
}

/** The slopes a problem is on, as bulletins draw them: a compass of eight, north up, filled where it applies. */
@Composable
fun AspectRose(aspects: List<String>, size: Dp) {
    val on = MaterialTheme.colorScheme.onSurface
    val off = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
    val gap = MaterialTheme.colorScheme.surfaceVariant
    val description = aspects.joinToString(" ")
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.semantics { contentDescription = description }) {
        Text("N", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Canvas(Modifier.size(size)) {
            for ((i, aspect) in Bulletin.ASPECTS.withIndex()) {
                val start = -90f - 22.5f + i * 45f
                drawArc(if (aspect in aspects) on else off, start, 45f, useCenter = true)
                drawArc(gap, start, 45f, useCenter = true, style = Stroke(width = 2.dp.toPx()))
            }
        }
    }
}

/** The bulletin's own texts, for who wants them all. */
@Composable
private fun FullBulletin(b: Bulletin, onDismiss: () -> Unit) {
    val uriHandler = LocalUriHandler.current
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.padding(vertical = 16.dp)) {
                Text(
                    b.regionName ?: stringResource(R.string.avalanche_title),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                )
                SelectionContainer(Modifier.weight(1f, fill = false)) {
                    Column(
                        Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        // Some services write about the avalanches as a whole, others problem by problem.
                        val activity = b.activity ?: b.problems.mapNotNull { it.comment }.distinct().joinToString("\n\n").takeIf { it.isNotEmpty() }
                        for ((title, text) in listOf(
                            R.string.avalanche_activity to activity,
                            R.string.avalanche_snowpack to b.snowpack,
                            R.string.avalanche_outlook to b.outlook,
                        )) {
                            text ?: continue
                            Text(stringResource(title), style = MaterialTheme.typography.titleSmall)
                            Text(text, style = MaterialTheme.typography.bodyMedium)
                        }
                        b.provider?.let {
                            Text(
                                stringResource(R.string.avalanche_by, it),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                Row(Modifier.align(Alignment.End).padding(end = 12.dp)) {
                    b.website?.let { site ->
                        TextButton(onClick = { runCatching { uriHandler.openUri(site) } }, modifier = Modifier.heightIn(min = TouchTarget)) {
                            Text(stringResource(R.string.avalanche_website))
                        }
                    }
                    TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = TouchTarget)) {
                        Text(stringResource(R.string.weather_hours_close))
                    }
                }
            }
        }
    }
}

@Composable
private fun period(p: Bulletin.Period): String? = when (p) {
    Bulletin.Period.AllDay -> null
    Bulletin.Period.Earlier -> stringResource(R.string.avalanche_morning)
    Bulletin.Period.Later -> stringResource(R.string.avalanche_afternoon)
}

/** "Above 2,000 m", "Below the treeline", "Between 1,800 m and 2,400 m"; null for every height. */
@Composable
private fun heights(e: Bulletin.Elevation): String? {
    val lower = e.lower?.let { bound(it) }
    val upper = e.upper?.let { bound(it) }
    return when {
        lower != null && upper != null -> stringResource(R.string.avalanche_between, lower, upper)
        lower != null -> stringResource(R.string.avalanche_above, lower)
        upper != null -> stringResource(R.string.avalanche_below, upper)
        else -> null
    }
}

@Composable
private fun band(b: Bulletin.Band): String = stringResource(
    when (b) {
        Bulletin.Band.Alpine -> R.string.avalanche_band_alpine
        Bulletin.Band.Treeline -> R.string.avalanche_band_treeline
        Bulletin.Band.BelowTreeline -> R.string.avalanche_band_below_treeline
    },
)

@Composable
private fun bound(value: String): String =
    if (value.equals("treeline", ignoreCase = true)) stringResource(R.string.avalanche_treeline)
    else value.toDoubleOrNull()?.let(Format::elevation) ?: value

private fun dayTime(millis: Long): String =
    SimpleDateFormat("EEE", Locale.getDefault()).format(Date(millis)) + " " +
        DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(millis))

/** The European avalanche danger scale's colours, levels 1 to 4 (5 is red and black). */
private val LEVEL_COLORS = listOf(Color(0xFFCCFF66), Color(0xFFFFFF00), Color(0xFFFF9900), Color(0xFFFF0000))

private val LEVEL_NAMES = listOf(
    R.string.avalanche_level_1, R.string.avalanche_level_2, R.string.avalanche_level_3,
    R.string.avalanche_level_4, R.string.avalanche_level_5,
)

private val PROBLEM_NAMES = mapOf(
    "new_snow" to R.string.avalanche_problem_new_snow,
    "wind_slab" to R.string.avalanche_problem_wind_slab,
    "persistent_weak_layers" to R.string.avalanche_problem_persistent_weak_layers,
    "wet_snow" to R.string.avalanche_problem_wet_snow,
    "gliding_snow" to R.string.avalanche_problem_gliding_snow,
    "cornices" to R.string.avalanche_problem_cornices,
    "favourable_situation" to R.string.avalanche_problem_favourable_situation,
    "no_distinct_avalanche_problem" to R.string.avalanche_problem_none,
    "storm_slab" to R.string.avalanche_problem_storm_slab,
    "persistent_slab" to R.string.avalanche_problem_persistent_slab,
    "deep_persistent_slab" to R.string.avalanche_problem_deep_persistent_slab,
    "wet_slab" to R.string.avalanche_problem_wet_slab,
    "loose_wet" to R.string.avalanche_problem_loose_wet,
    "loose_dry" to R.string.avalanche_problem_loose_dry,
)
