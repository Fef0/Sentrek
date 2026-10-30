package app.sentrek.home

import app.sentrek.map.GoToLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.sentrek.R
import app.sentrek.geo.Geo
import app.sentrek.geo.LatLon
import app.sentrek.ui.Format
import app.sentrek.ui.LocalTouch
import app.sentrek.ui.SentrekIcons
import app.sentrek.ui.TouchTarget

/** A place to walk to in a straight line, off the trails if need be (fog, snow, the car). */
data class GoTo(val name: String, val position: LatLon)

/**
 * Where the place is: an arrow that points at it (turned by the phone's compass when there is
 * one, so it points the real way; by the map's turn otherwise), how far, and the climb.
 */
@Composable
fun GoToPanel(target: GoTo, here: LatLon?, heading: Float?, mapBearing: Double, onStop: () -> Unit, modifier: Modifier = Modifier) {
    val distance = here?.let { Geo.distance(it, target.position) }
    val arrived = distance != null && distance < ARRIVED_M
    val haptics = LocalHapticFeedback.current
    LaunchedEffect(arrived) { if (arrived) haptics.performHapticFeedback(HapticFeedbackType.LongPress) }

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
        contentColor = MaterialTheme.colorScheme.onSurface,
        shadowElevation = 4.dp,
    ) {
        Row(
            Modifier.padding(start = 12.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (here != null && !arrived) {
                val bearing = Geo.bearing(here, target.position)
                val turn = bearing - (heading?.toDouble() ?: mapBearing)
                Icon(
                    SentrekIcons.Heading,
                    contentDescription = Format.bearing(bearing),
                    // As the line on the map.
                    tint = Color(GoToLayer.COLOR),
                    modifier = Modifier.size(LocalTouch.current.icon * 1.6f).rotate(turn.toFloat()),
                )
            }
            Column(Modifier.weight(1f)) {
                Text(
                    when {
                        arrived -> stringResource(R.string.goto_arrived)
                        distance != null -> Format.distance(distance)
                        else -> stringResource(R.string.goto_waiting)
                    },
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                )
                val climb = here?.ele?.let { from -> target.position.ele?.let { it - from } }
                val details = listOfNotNull(
                    target.name,
                    here?.takeIf { !arrived }?.let { Format.bearing(Geo.bearing(it, target.position)) },
                    climb?.takeIf { !arrived && kotlin.math.abs(it) >= MIN_CLIMB_M }?.let { (if (it > 0) "↑ " else "↓ ") + Format.elevation(kotlin.math.abs(it)) },
                ).joinToString(" · ")
                Text(details, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconButton(onClick = onStop, modifier = Modifier.size(TouchTarget)) {
                Icon(SentrekIcons.Close, stringResource(R.string.goto_stop))
            }
        }
    }
}

/** Closer than this is "there": about what a phone GPS can tell apart. */
private const val ARRIVED_M = 20.0

/** Smaller differences are noise between the GPS and the map's heights. */
private const val MIN_CLIMB_M = 10.0
