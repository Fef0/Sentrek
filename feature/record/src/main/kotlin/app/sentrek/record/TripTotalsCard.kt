package app.sentrek.record

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.sentrek.feature.record.R
import app.sentrek.geo.track.Trip
import app.sentrek.geo.track.TripTotals
import app.sentrek.ui.Format
import app.sentrek.ui.TouchTarget
import java.util.Calendar
import java.util.Locale

/**
 * What the user has walked, this year or all time: one card above the tracks, a tap switches.
 * Nothing at all until there is a walk to count.
 */
@Composable
fun TripTotalsCard(tracks: List<SavedTrack>, allTime: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    val trips = remember(tracks) { tracks.map { Trip(it.startTime, it.distance, it.ascent, it.duration) } }
    if (TripTotals.of(trips).hikes == 0) return
    val totals = remember(trips, allTime) { TripTotals.of(trips, since = if (allTime) Long.MIN_VALUE else yearStart()) }
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = TouchTarget)
            .clickable(onClickLabel = stringResource(R.string.totals_switch), onClick = onToggle),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(
                stringResource(if (allTime) R.string.totals_all_time else R.string.totals_this_year),
                style = MaterialTheme.typography.labelLarge,
            )
            if (totals.hikes == 0) {
                Text(stringResource(R.string.totals_none), style = MaterialTheme.typography.titleMedium)
            } else {
                Text(
                    pluralStringResource(R.plurals.totals_hikes, totals.hikes, totals.hikes) + " · " + Format.distance(totals.distance),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "↑ ${Format.elevation(totals.ascent)} · ${hours(totals.movingMillis)}",
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }
    }
}

/** Moving time to the minute: "62:10 h". */
private fun hours(millis: Long): String {
    val minutes = millis / 60_000
    return String.format(Locale.ROOT, "%d:%02d h", minutes / 60, minutes % 60)
}

/** Midnight on 1 January this year, here. */
private fun yearStart(): Long = Calendar.getInstance().apply {
    set(Calendar.DAY_OF_YEAR, 1)
    set(Calendar.HOUR_OF_DAY, 0)
    set(Calendar.MINUTE, 0)
    set(Calendar.SECOND, 0)
    set(Calendar.MILLISECOND, 0)
}.timeInMillis
