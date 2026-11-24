package app.sentrek.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.sentrek.R
import app.sentrek.feature.planner.R as PlannerR
import app.sentrek.planner.PlanStats
import app.sentrek.ui.Format
import app.sentrek.ui.SentrekIcons
import app.sentrek.ui.TouchTarget

/**
 * "Take me there" from search: the way along the paths from the user to a place. Shown on the
 * map, not saved; following it is the user's choice.
 */
sealed interface WayThere {
    val target: GoTo

    /** Waiting for the GPS, then working the way out. */
    data class Finding(override val target: GoTo) : WayThere

    data class Found(override val target: GoTo, val stats: PlanStats) : WayThere

    /** The paths don't join up, or none are known there. */
    data class NotFound(override val target: GoTo) : WayThere
}

/** The way there, its totals, and Follow; or why there's none, and the straight line instead. */
@Composable
fun WayTherePanel(
    way: WayThere,
    onFollow: () -> Unit,
    onStraightLine: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
        contentColor = MaterialTheme.colorScheme.onSurface,
        shadowElevation = 4.dp,
    ) {
        Column(Modifier.padding(start = 12.dp, top = 4.dp, bottom = if (way is WayThere.Finding) 4.dp else 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (way is WayThere.Finding) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.5.dp)
                Text(
                    stringResource(if (way is WayThere.Finding) R.string.way_finding else R.string.way_to, way.target.name),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onClose, modifier = Modifier.size(TouchTarget)) {
                    Icon(SentrekIcons.Close, stringResource(R.string.way_close))
                }
            }
            if (way !is WayThere.Finding) Column(Modifier.padding(end = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when (way) {
                    is WayThere.Finding -> Unit
                    is WayThere.Found -> {
                        val s = way.stats
                        // As the planner shows a route's totals.
                        Row {
                            WayStat(stringResource(PlannerR.string.plan_distance), Format.distance(s.distance), Modifier.weight(1f))
                            WayStat(stringResource(PlannerR.string.plan_ascent), if (s.hasElevation) "↑ " + Format.elevation(s.ascent) else "–", Modifier.weight(1f))
                            WayStat(stringResource(PlannerR.string.plan_descent), if (s.hasElevation) "↓ " + Format.elevation(s.descent) else "–", Modifier.weight(1f))
                            WayStat(stringResource(PlannerR.string.plan_time), Format.estimate(s.millis), Modifier.weight(1f))
                        }
                        Button(onClick = onFollow, modifier = Modifier.heightIn(min = TouchTarget)) {
                            Icon(SentrekIcons.Hiking, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                            Text(stringResource(R.string.way_follow))
                        }
                    }
                    is WayThere.NotFound -> {
                        Text(stringResource(R.string.way_none), style = MaterialTheme.typography.bodyLarge)
                        OutlinedButton(onClick = onStraightLine, modifier = Modifier.heightIn(min = TouchTarget)) {
                            Text(stringResource(R.string.way_straight_line))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WayStat(label: String, value: String, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1)
        Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1)
    }
}
