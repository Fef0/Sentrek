package app.sentrek.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.sentrek.R
import app.sentrek.geo.overlay.PoiKind
import app.sentrek.geo.place.PlaceKind
import app.sentrek.geo.route.RoutePlace
import app.sentrek.geo.route.RoutePlaces
import app.sentrek.geo.route.UpcomingPlace
import app.sentrek.map.MapIcons
import app.sentrek.record.PlaceIcon
import app.sentrek.record.placeKindLabel
import app.sentrek.ui.Format
import app.sentrek.ui.SentrekIcons
import app.sentrek.ui.TouchTarget

/**
 * The places still ahead on the followed route. One line for the next place (how far, how
 * much climb, how long); a tap opens the rest of the list, another closes it again.
 */
@Composable
fun PlacesAhead(upcoming: List<UpcomingPlace>, showClimb: Boolean, modifier: Modifier = Modifier) {
    if (upcoming.isEmpty()) return
    var expanded by rememberSaveable { mutableStateOf(false) }
    Column(modifier) {
        HorizontalDivider(color = LocalContentColor.current.copy(alpha = 0.2f))
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = TouchTarget)
                .clickable(
                    onClickLabel = stringResource(if (expanded) R.string.follow_places_less else R.string.follow_places_more),
                    onClick = { expanded = !expanded },
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AheadRow(upcoming.first(), showClimb, Modifier.weight(1f))
            if (upcoming.size > 1) {
                Text("+${upcoming.size - 1}", style = MaterialTheme.typography.labelLarge)
                Icon(if (expanded) SentrekIcons.ExpandLess else SentrekIcons.ExpandMore, contentDescription = null)
            }
        }
        if (expanded && upcoming.size > 1) {
            // A few rows tall at most: the map stays the main thing on screen.
            Column(
                Modifier
                    .heightIn(max = 200.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                for (place in upcoming.drop(1)) {
                    AheadRow(place, showClimb, Modifier.heightIn(min = TouchTarget))
                }
            }
        }
    }
}

@Composable
private fun AheadRow(ahead: UpcomingPlace, showClimb: Boolean, modifier: Modifier = Modifier) {
    val place = ahead.place
    Row(modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        PlaceBadge(place.kind, 28.dp)
        Column(Modifier.weight(1f)) {
            Text(
                place.name ?: kindLabel(place.kind),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val details = if (ahead.distance < RoutePlaces.REACHED_M) {
                stringResource(R.string.follow_place_here)
            } else {
                listOfNotNull(
                    Format.distance(ahead.distance),
                    ("↑ " + Format.elevation(ahead.ascent)).takeIf { showClimb && ahead.ascent >= MIN_CLIMB_M },
                    Format.estimate(ahead.millis),
                ).joinToString(" · ")
            }
            Text(details, style = MaterialTheme.typography.labelLarge, maxLines = 1)
        }
    }
}

/** As on the map: the symbol in its colour on a white disc. */
@Composable
private fun PlaceBadge(kind: RoutePlace.Kind, size: Dp) {
    when (kind) {
        is RoutePlace.Kind.Saved -> PlaceIcon(kind.kind, kind.color, size)
        RoutePlace.Kind.Waypoint -> PlaceIcon(PlaceKind.Star, WAYPOINT_COLOR, size)
        is RoutePlace.Kind.Osm -> {
            val (symbol, color) = MapIcons.symbol(kind.kind.icon) ?: return
            Surface(
                modifier = Modifier.size(size),
                shape = CircleShape,
                color = Color.White,
                border = BorderStroke(1.5.dp, Color(color)),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(painterResource(symbol), contentDescription = null, tint = Color(color), modifier = Modifier.size(size * 0.6f))
                }
            }
        }
    }
}

@Composable
private fun kindLabel(kind: RoutePlace.Kind): String = when (kind) {
    is RoutePlace.Kind.Saved -> placeKindLabel(kind.kind)
    RoutePlace.Kind.Waypoint -> stringResource(R.string.ahead_waypoint)
    is RoutePlace.Kind.Osm -> stringResource(
        when (kind.kind) {
            PoiKind.AlpineHut -> R.string.ahead_alpine_hut
            PoiKind.WildernessHut -> R.string.ahead_wilderness_hut
            PoiKind.Shelter -> R.string.ahead_shelter
            PoiKind.Spring -> R.string.ahead_spring
            PoiKind.DrinkingWater -> R.string.ahead_drinking_water
            PoiKind.Cave -> R.string.ahead_cave
            PoiKind.Viewpoint -> R.string.ahead_viewpoint
            PoiKind.Waterfall -> R.string.ahead_waterfall
            PoiKind.Camping -> R.string.ahead_camping
            PoiKind.EmergencyPhone -> R.string.ahead_emergency_phone
            PoiKind.Peak -> R.string.ahead_peak
            PoiKind.Pass -> R.string.ahead_pass
            // Not listed as places ahead (see PoiKind.landmark); a readable name all the same.
            else -> R.string.ahead_waypoint
        },
    )
}

/** Less climb than this is noise on the way to a place, not worth a number. */
private const val MIN_CLIMB_M = 10.0

private const val WAYPOINT_COLOR = 0xFF455A64.toInt()
