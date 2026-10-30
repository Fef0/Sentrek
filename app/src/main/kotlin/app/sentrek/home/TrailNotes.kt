package app.sentrek.home

import app.sentrek.geo.route.Shade
import app.sentrek.geo.weather.Hourly
import app.sentrek.geo.weather.WetAhead
import app.sentrek.ui.Format
import app.sentrek.geo.route.WaterAhead
import android.text.format.DateFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.sentrek.R
import app.sentrek.data.PowerMode
import app.sentrek.record.BatteryWatch
import app.sentrek.ui.SentrekIcons
import app.sentrek.ui.TouchTarget
import java.util.Date

/**
 * What's coming that the user should know about (the battery, water, rain), each a line under
 * the recording or route panel, and only while there's something to say: most of the time
 * there's nothing here at all.
 */
@Composable
fun TrailNotes(
    battery: BatteryWatch.State?,
    /** When the user reaches the end of the followed route; null without one. */
    arrival: Long?,
    powerMode: PowerMode,
    onStamina: () -> Unit,
    modifier: Modifier = Modifier,
    /** Water on the followed route; null without a route, or no water known on it. */
    water: WaterAhead? = null,
    /** The first wet spot on the way, null while dry or unknown. */
    wet: WetAhead.Found? = null,
    /** The next long stretch in the sun on the route, or the one the user is in. */
    sunny: Shade.Stretch? = null,
) {
    val now = rememberNow()
    val empty = battery?.emptyAt?.takeIf { BatteryWatch.tooSoon(it, arrival, now) }
    val dry = water?.takeIf { it.long }
    if (empty == null && dry == null && wet == null && sunny == null) return
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (wet != null) WetNote(wet, onRoute = arrival != null, now = now)
        if (sunny != null) SunNote(sunny, now)
        if (dry != null) WaterNote(dry)
        if (empty != null) TrailNote(
            icon = SentrekIcons.BatteryAlert,
            text = if (arrival != null) {
                stringResource(R.string.note_battery_route, time(empty), time(arrival))
            } else {
                stringResource(R.string.note_battery, time(empty))
            },
            warning = true,
            action = if (powerMode != PowerMode.Stamina) stringResource(R.string.note_battery_stamina) else null,
            onAction = onStamina,
        )
    }
}

/** Rain, snow or a storm, from when and how far on; a storm is a warning. */
@Composable
private fun WetNote(wet: WetAhead.Found, onRoute: Boolean, now: Long) {
    val what = stringResource(
        when (wet.wet) {
            Hourly.Wet.Rain -> R.string.note_wet_rain
            Hourly.Wet.Snow -> R.string.note_wet_snow
            Hourly.Wet.Storm -> R.string.note_wet_storm
        },
    )
    val text = when {
        wet.from <= now + NOW_MS -> stringResource(R.string.note_wet_now, what)
        onRoute && wet.ahead >= NEAR_AHEAD_M -> {
            val height = wet.elevation?.let { stringResource(R.string.note_wet_height, Format.elevation(it)) }.orEmpty()
            stringResource(R.string.note_wet_route, what, time(wet.from), Format.distance(wet.ahead), height)
        }
        else -> stringResource(R.string.note_wet_from, what, time(wet.from))
    }
    TrailNote(
        if (wet.wet == Hourly.Wet.Storm) SentrekIcons.Storm else SentrekIcons.Rain,
        text,
        warning = wet.wet == Hourly.Wet.Storm,
    )
}

/** In the sun now and for how long, or from when, how far on and for how long. */
@Composable
private fun SunNote(stretch: Shade.Stretch, now: Long) {
    val text = if (stretch.start <= now + NOW_MS) {
        stringResource(R.string.note_sun_now, Format.hoursMinutes(stretch.end - now))
    } else {
        stringResource(R.string.note_sun_from, time(stretch.start), Format.distance(stretch.from), Format.hoursMinutes(stretch.millis))
    }
    TrailNote(SentrekIcons.Sun, text, warning = false)
}

/** The last water before a long dry stretch, or none left at all. */
@Composable
private fun WaterNote(water: WaterAhead) {
    val next = water.next
    val text = if (next != null) {
        stringResource(
            R.string.note_water_last,
            Format.distance(water.dryDistance),
            next.place.name ?: stringResource(R.string.note_water_unnamed),
            Format.distance(next.distance),
        )
    } else {
        stringResource(R.string.note_water_none, Format.distance(water.dryDistance))
    }
    // Close to it, it's time to act: fill up there.
    TrailNote(SentrekIcons.Water, text, warning = next != null && next.distance <= WATER_NEAR_M)
}

@Composable
private fun time(millis: Long): String = DateFormat.getTimeFormat(LocalContext.current).format(Date(millis))

/** One note: amber for a [warning] (as the sunset one), quiet otherwise; an optional [action]. */
@Composable
fun TrailNote(
    icon: ImageVector,
    text: String,
    warning: Boolean,
    modifier: Modifier = Modifier,
    action: String? = null,
    onAction: () -> Unit = {},
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = if (warning) NoteAmber else MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
        contentColor = if (warning) Color.Black else MaterialTheme.colorScheme.onSurface,
    ) {
        Row(
            Modifier.padding(start = 12.dp, end = if (action != null) 4.dp else 12.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
            Text(
                text,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (warning) FontWeight.Bold else FontWeight.Normal,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (action != null) {
                TextButton(onClick = onAction, modifier = Modifier.heightIn(min = TouchTarget)) {
                    Text(action, fontWeight = FontWeight.Bold, color = if (warning) Color.Black else MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

private const val WATER_NEAR_M = 500.0

/** Closer than this, "from now"; nearer than this on the route, "here". */
private const val NOW_MS = 10 * 60_000L
private const val NEAR_AHEAD_M = 500.0

/** As the sunset warning: a caution, not an error. */
private val NoteAmber = Color(0xFFFFB300)
