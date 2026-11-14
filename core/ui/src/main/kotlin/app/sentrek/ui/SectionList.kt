package app.sentrek.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.sentrek.core.ui.R
import app.sentrek.geo.plan.SectionTotals

/** The section ends' colour on the map (PlanLayers). */
private val SECTION_ORANGE = Color(0xFFEF6C00)

/**
 * Where on [profile] each section but the last ends: the sections' lengths added up, fitted to
 * the profile's length (they're measured on their own pieces, a few metres apart).
 */
fun sectionFlags(sections: List<SectionTotals>, profile: List<Pair<Double, Double>>): List<Double> {
    val total = sections.sumOf { it.distance }
    val length = profile.lastOrNull()?.first ?: return emptyList()
    if (sections.size < 2 || total <= 0) return emptyList()
    var along = 0.0
    return sections.dropLast(1).map { along += it.distance; along / total * length }
}

/** A route's sections, one each: its number, distance, climb, descent, time, and the heights it starts and ends at. */
@Composable
fun SectionList(sections: List<SectionTotals>, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        sections.forEachIndexed { i, s ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                // As on the map: the number in an orange dot, where the section ends.
                val label = stringResource(R.string.section_number, i + 1)
                Box(
                    Modifier.size(24.dp).background(SECTION_ORANGE, CircleShape).semantics { contentDescription = label },
                    contentAlignment = Alignment.Center,
                ) {
                    Text("${i + 1}", color = Color.White, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                }
                val climb = if (s.hasElevation) " · ↑ ${Format.elevation(s.ascent)} ↓ ${Format.elevation(s.descent)}" else ""
                Column {
                    Text(
                        "${Format.distance(s.distance)}$climb · ${Format.estimate(s.millis)}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    // The heights it goes between: where the night's stop is, how high.
                    val from = s.startEle
                    val to = s.endEle
                    if (from != null && to != null) {
                        Text(
                            stringResource(R.string.section_heights, Format.elevation(from), Format.elevation(to)),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
