package app.sentrek.home

import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import app.sentrek.geo.LatLon
import app.sentrek.geo.Sun
import kotlinx.coroutines.delay
import java.util.Date

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

/** Today's sunset at [position], e.g. "17:22", or null when the sun doesn't set (or has). */
@Composable
fun sunsetToday(position: LatLon): String? {
    val now = rememberNow()
    val sunset = Sun.day(now, position.lat, position.lon)?.sunset ?: return null
    return DateFormat.getTimeFormat(LocalContext.current).format(Date(sunset))
}

private const val TICK_MS = 30_000L
