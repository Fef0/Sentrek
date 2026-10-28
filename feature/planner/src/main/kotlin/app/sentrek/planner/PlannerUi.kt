package app.sentrek.planner

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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

/** Totals of the route being planned and its elevation profile; a hint until there are two points. */
@Composable
fun PlanPanel(stats: PlanStats?, editing: String?, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
        // See-through, so not matched to a theme colour: name the text colour.
        contentColor = MaterialTheme.colorScheme.onSurface,
        shadowElevation = 4.dp,
    ) {
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
            Row {
                Stat(stringResource(R.string.plan_distance), Format.distance(stats.distance), Modifier.weight(1f))
                Stat(stringResource(R.string.plan_ascent), if (stats.hasElevation) "↑ " + Format.meters(stats.ascent) else "–", Modifier.weight(1f))
                Stat(stringResource(R.string.plan_descent), if (stats.hasElevation) "↓ " + Format.meters(stats.descent) else "–", Modifier.weight(1f))
                Stat(stringResource(R.string.plan_time), Format.estimate(stats.millis), Modifier.weight(1f))
            }
            if (stats.hasElevation) {
                ElevationProfile(stats.profile, Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp))
            } else {
                Text(
                    stringResource(R.string.plan_no_terrain),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 4.dp),
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
        confirmButton = {
            Button(
                onClick = onDiscard,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
            ) { Text(stringResource(R.string.plan_discard)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.plan_keep)) } },
    )
}
