package app.sentrek.planner

import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import app.sentrek.geo.plan.SectionTotals
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.draw.alpha
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.sentrek.feature.planner.R
import app.sentrek.ui.ElevationProfile
import app.sentrek.ui.Format
import app.sentrek.ui.SectionList
import app.sentrek.ui.sectionFlags

/**
 * Totals of the route being planned and its elevation profile; a hint until there are two
 * points. While legs are worked out along the paths ([routing]) the numbers are faded under a
 * spinner: they're about to change.
 */
@Composable
fun PlanPanel(stats: PlanStats?, editing: String?, modifier: Modifier = Modifier, routing: Boolean = false) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
        // See-through, so not matched to a theme colour: name the text colour.
        contentColor = MaterialTheme.colorScheme.onSurface,
        shadowElevation = 4.dp,
    ) {
        if (stats == null && editing == null && routing) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.5.dp)
                Text(stringResource(R.string.plan_routing), style = MaterialTheme.typography.bodyLarge)
            }
            return@Surface
        }
        if (stats == null && editing == null) {
            Text(
                stringResource(R.string.plan_hint),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(12.dp),
            )
            return@Surface
        }
        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            editing?.let {
                Text(
                    stringResource(R.string.plan_editing, it),
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
            if (stats == null) return@Column
            val faded = if (routing) Modifier.alpha(0.3f) else Modifier
            Row(faded) {
                Stat(stringResource(R.string.plan_distance), Format.distance(stats.distance), Modifier.weight(1f))
                Stat(stringResource(R.string.plan_ascent), if (stats.hasElevation) "↑ " + Format.elevation(stats.ascent) else "–", Modifier.weight(1f))
                Stat(stringResource(R.string.plan_descent), if (stats.hasElevation) "↓ " + Format.elevation(stats.descent) else "–", Modifier.weight(1f))
                Stat(stringResource(R.string.plan_time), Format.estimate(stats.millis), Modifier.weight(1f))
            }
            if (stats.hasElevation) {
                Box(contentAlignment = Alignment.Center) {
                    ElevationProfile(
                        stats.profile,
                        Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp).then(faded),
                        flags = sectionFlags(stats.sections, stats.profile),
                    )
                    if (routing) CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
                }
            } else if (routing) {
                Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.5.dp)
                    Text(stringResource(R.string.plan_routing), style = MaterialTheme.typography.bodyMedium)
                }
            } else {
                Text(
                    stringResource(R.string.plan_no_terrain),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
            // A long trek has many: they scroll, and the map stays in sight.
            if (stats.sections.isNotEmpty()) {
                SectionList(
                    stats.sections,
                    Modifier.padding(horizontal = 4.dp).heightIn(max = 136.dp).verticalScroll(rememberScrollState()),
                )
            }
        }
    }
}

@Composable
private fun Stat(label: String, value: String, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1)
        Text(label, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun DiscardPlanDialog(onDiscard: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.plan_discard_title)) },
        text = { Text(stringResource(R.string.plan_discard_text)) },
        // Keeping on is the safe choice: the filled button, on the right.
        confirmButton = { Button(onClick = onDismiss) { Text(stringResource(R.string.plan_keep)) } },
        dismissButton = {
            TextButton(
                onClick = onDiscard,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) { Text(stringResource(R.string.plan_discard)) }
        },
    )
}
