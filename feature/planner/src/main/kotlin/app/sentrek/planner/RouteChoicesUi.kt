package app.sentrek.planner

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.sentrek.feature.planner.R
import app.sentrek.geo.plan.RouteOptions
import app.sentrek.map.PlanLayers
import app.sentrek.ui.Format
import app.sentrek.ui.TouchTarget

/** The short name of a route profile, for the planner's button. */
@Composable
fun profileName(profile: RouteOptions.Profile): String = stringResource(
    when (profile) {
        RouteOptions.Profile.Shortest -> R.string.route_shortest
        RouteOptions.Profile.Easiest -> R.string.route_easiest
        RouteOptions.Profile.LeastClimb -> R.string.route_least_climb
    },
)

/**
 * How the route picks its way along the paths: shortest, easiest or least climb, via
 * ferratas or not, and the spots closed on the map. Saved with the route; a change routes
 * every leg along the paths again.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RouteOptionsSheet(options: RouteOptions, onChange: (RouteOptions) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(stringResource(R.string.route_options_title), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.route_options_hint), style = MaterialTheme.typography.bodyMedium)
            for ((profile, hint) in listOf(
                RouteOptions.Profile.Shortest to R.string.route_shortest_hint,
                RouteOptions.Profile.Easiest to R.string.route_easiest_hint,
                RouteOptions.Profile.LeastClimb to R.string.route_least_climb_hint,
            )) {
                val selected = options.profile == profile
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = TouchTarget)
                        .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent, RoundedCornerShape(12.dp))
                        .selectable(selected, role = Role.RadioButton) { onChange(options.copy(profile = profile)) }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(profileName(profile), style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(hint), style = MaterialTheme.typography.bodyMedium)
                    }
                    RadioButton(selected = selected, onClick = null)
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = TouchTarget)
                    .toggleable(options.avoidFerratas, role = Role.Switch) { onChange(options.copy(avoidFerratas = it)) }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(stringResource(R.string.route_avoid_ferratas), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Switch(checked = options.avoidFerratas, onCheckedChange = null)
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = TouchTarget)
                    .toggleable(options.avoidRoads, role = Role.Switch) { onChange(options.copy(avoidRoads = it)) }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.route_avoid_roads), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.route_avoid_roads_hint), style = MaterialTheme.typography.bodyMedium)
                }
                Switch(checked = options.avoidRoads, onCheckedChange = null)
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Text(stringResource(R.string.route_avoided_title), style = MaterialTheme.typography.titleMedium)
            val avoided = options.avoided.size
            Text(
                if (avoided == 0) stringResource(R.string.route_avoided_none) else pluralStringResourceCompat(avoided),
                style = MaterialTheme.typography.bodyMedium,
            )
            if (avoided > 0) {
                OutlinedButton(
                    onClick = { onChange(options.copy(avoided = emptyList())) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = TouchTarget),
                ) { Text(stringResource(R.string.route_avoided_clear)) }
            }
        }
    }
}

@Composable
private fun pluralStringResourceCompat(count: Int): String =
    androidx.compose.ui.res.pluralStringResource(R.plurals.route_avoided_count, count, count)

/**
 * The other ways for one leg, numbered as on the map, each with its distance, climb and time.
 * Tapping one draws it as the leg ([onShow]); the button keeps the one drawn ([onChoose]).
 */
@Composable
fun WayChoicePanel(
    choices: LegChoices,
    onShow: (Int) -> Unit,
    onChoose: (Int) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
        contentColor = MaterialTheme.colorScheme.onSurface,
        shadowElevation = 4.dp,
    ) {
        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.route_other_ways_title),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
                )
                TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = TouchTarget)) {
                    Text(stringResource(R.string.route_other_ways_close))
                }
            }
            when {
                choices.loading -> Row(
                    Modifier.padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text(stringResource(R.string.route_other_ways_looking), style = MaterialTheme.typography.bodyMedium)
                }
                choices.ways.size < 2 -> Text(
                    stringResource(R.string.route_other_ways_none),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(8.dp),
                )
                else -> {
                    choices.ways.forEachIndexed { i, way ->
                        val shown = i == choices.shown
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = TouchTarget)
                                .background(if (shown) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent, RoundedCornerShape(12.dp))
                                .selectable(shown, role = Role.RadioButton) { onShow(i) }
                                .padding(horizontal = 6.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            // In the way's colour, as on the map.
                            val badge = PlanLayers.WAY_COLORS[i % PlanLayers.WAY_COLORS.size]
                            Box(Modifier.size(28.dp).background(Color(badge), CircleShape), contentAlignment = Alignment.Center) {
                                Text("${i + 1}", color = Color.White, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                            }
                            val climb = if (way.hasElevation) " · ↑ ${Format.elevation(way.ascent)}" else ""
                            Text(
                                "${Format.distance(way.distance)}$climb · ${Format.estimate(way.millis)}",
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 1,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                    Button(
                        onClick = { onChoose(choices.shown) },
                        modifier = Modifier.fillMaxWidth().heightIn(min = TouchTarget),
                    ) { Text(stringResource(R.string.route_other_ways_use, choices.shown + 1)) }
                }
            }
        }
    }
}
