package app.sentrek.home

import android.text.format.DateFormat
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.sentrek.R
import app.sentrek.geo.LatLon
import app.sentrek.geo.Sun
import kotlinx.coroutines.delay
import java.util.Date
import java.util.Locale

/** The time now, updated every half minute: enough for a countdown in minutes. */
@Composable
fun rememberNow(): Long {
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(TICK_MS)
            value = System.currentTimeMillis()
        }
    }
    return now
}

/**
 * The coming dark, only when it matters: a warning when the walking time left ([remainingMillis])
 * ends after sunset, otherwise a quiet countdown in the last hours of daylight. Nothing at
 * other times, after sunset, or in polar day and night.
 */
@Composable
fun SunsetLine(position: LatLon?, remainingMillis: Long?, modifier: Modifier = Modifier) {
    val here = position ?: return
    val now = rememberNow()
    val sunset = Sun.day(now, here.lat, here.lon)?.sunset ?: return
    if (now >= sunset) return
    val context = LocalContext.current
    val at = DateFormat.getTimeFormat(context).format(Date(sunset))
    val arrival = remainingMillis?.let { now + it }
    when {
        arrival != null && arrival > sunset -> Surface(
            modifier = modifier,
            shape = RoundedCornerShape(8.dp),
            color = Amber,
            contentColor = Color.Black,
        ) {
            Text(
                stringResource(R.string.sun_dark_before_arrival, at, DateFormat.getTimeFormat(context).format(Date(arrival))),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
        sunset - now <= SOON_MS -> Text(
            stringResource(R.string.sun_sunset_in, at, hoursMinutes(sunset - now)),
            style = MaterialTheme.typography.labelLarge,
            modifier = modifier,
        )
    }
}

/** Today's sunset at [position], e.g. "17:22", or null when the sun doesn't set (or has). */
@Composable
fun sunsetToday(position: LatLon): String? {
    val now = rememberNow()
    val sunset = Sun.day(now, position.lat, position.lon)?.sunset ?: return null
    return DateFormat.getTimeFormat(LocalContext.current).format(Date(sunset))
}

/** "1:40 h", "0:25 h". */
private fun hoursMinutes(millis: Long): String {
    val minutes = (millis / 60_000).coerceAtLeast(0)
    return String.format(Locale.ROOT, "%d:%02d h", minutes / 60, minutes % 60)
}

/** As GpsUi's warnings: a caution, not an error. */
private val Amber = Color(0xFFFFB300)

private const val TICK_MS = 30_000L

/** The last hours of daylight, when the time of sunset starts to matter. */
private const val SOON_MS = 2 * 3_600_000L
