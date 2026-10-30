package app.sentrek.home

import app.sentrek.geo.LatLon
import app.sentrek.geo.Sun
import app.sentrek.geo.route.Shade
import app.sentrek.geo.weather.Hourly
import app.sentrek.geo.weather.WetAhead
import app.sentrek.ui.Format
import app.sentrek.geo.route.WaterAhead
import android.text.format.DateFormat
import androidx.compose.foundation.layout.Arrangement
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

/** One thing the user should know about on the way, with an optional [action]. */
class Note(
    val icon: ImageVector,
    val text: String,
    /** Amber: something to act on. */
    val warning: Boolean,
    val action: String? = null,
    val onAction: () -> Unit = {},
)

/**
 * What's coming that the user should know about (the dark, the battery, rain, water, sun),
 * only while there's something to say: most of the time the list is empty. Warnings first,
 * so the trail card can show just the first one.
 */
@Composable
fun rememberTrailNotes(
    battery: BatteryWatch.State?,
    /** When the user reaches the end of the followed route; null without one. */
    arrival: Long?,
    powerMode: PowerMode,
    onStamina: () -> Unit,
    /** Where the user is (or the map, before a fix): for the time of sunset. */
    position: LatLon?,
    /** Water on the followed route; null without a route, or no water known on it. */
    water: WaterAhead? = null,
    /** The first wet spot on the way, null while dry or unknown. */
    wet: WetAhead.Found? = null,
    /** The next long stretch in the sun on the route, or the one the user is in. */
    sunny: Shade.Stretch? = null,
): List<Note> {
    val now = rememberNow()
    val notes = mutableListOf<Note>()
    position?.let { sunsetNote(it, arrival, now) }?.let(notes::add)
    battery?.emptyAt?.takeIf { BatteryWatch.tooSoon(it, arrival, now) }?.let { empty ->
        notes += Note(
            SentrekIcons.BatteryAlert,
            if (arrival != null) {
                stringResource(R.string.note_battery_route, time(empty), time(arrival))
            } else {
                stringResource(R.string.note_battery, time(empty))
            },
            warning = true,
            action = if (powerMode != PowerMode.Stamina) stringResource(R.string.note_battery_stamina) else null,
            onAction = onStamina,
        )
    }
    if (wet != null) notes += wetNote(wet, onRoute = arrival != null, now = now)
    water?.takeIf { it.long }?.let { notes += waterNote(it) }
    if (sunny != null) notes += sunNote(sunny, now)
    return notes.sortedByDescending { it.warning }
}

/**
 * The coming dark, only when it matters: a warning when the walk ends after sunset, otherwise
 * a quiet countdown in the last hours of daylight. Nothing at other times, after sunset, or
 * in polar day and night.
 */
@Composable
private fun sunsetNote(here: LatLon, arrival: Long?, now: Long): Note? {
    val sunset = Sun.day(now, here.lat, here.lon)?.sunset ?: return null
    if (now >= sunset) return null
    return when {
        arrival != null && arrival > sunset ->
            Note(SentrekIcons.Sun, stringResource(R.string.sun_dark_before_arrival, time(sunset), time(arrival)), warning = true)
        sunset - now <= SUNSET_SOON_MS ->
            Note(SentrekIcons.Sun, stringResource(R.string.sun_sunset_in, time(sunset), Format.hoursMinutes(sunset - now)), warning = false)
        else -> null
    }
}

/** Rain, snow or a storm, from when and how far on; a storm is a warning. */
@Composable
private fun wetNote(wet: WetAhead.Found, onRoute: Boolean, now: Long): Note {
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
    return Note(
        if (wet.wet == Hourly.Wet.Storm) SentrekIcons.Storm else SentrekIcons.Rain,
        text,
        warning = wet.wet == Hourly.Wet.Storm,
    )
}

/** In the sun now and for how long, or from when, how far on and for how long. */
@Composable
private fun sunNote(stretch: Shade.Stretch, now: Long): Note {
    val text = if (stretch.start <= now + NOW_MS) {
        stringResource(R.string.note_sun_now, Format.hoursMinutes(stretch.end - now))
    } else {
        stringResource(R.string.note_sun_from, time(stretch.start), Format.distance(stretch.from), Format.hoursMinutes(stretch.millis))
    }
    return Note(SentrekIcons.Sun, text, warning = false)
}

/** The last water before a long dry stretch, or none left at all. */
@Composable
private fun waterNote(water: WaterAhead): Note {
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
    return Note(SentrekIcons.Water, text, warning = next != null && next.distance <= WATER_NEAR_M)
}

@Composable
private fun time(millis: Long): String = DateFormat.getTimeFormat(LocalContext.current).format(Date(millis))

/** One note: amber for a warning, quiet otherwise. */
@Composable
fun TrailNote(note: Note, modifier: Modifier = Modifier) {
    val warning = note.warning
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = if (warning) NoteAmber else MaterialTheme.colorScheme.surfaceVariant,
        contentColor = if (warning) Color.Black else MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Row(
            Modifier.padding(start = 12.dp, end = if (note.action != null) 4.dp else 12.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(note.icon, contentDescription = null, modifier = Modifier.size(20.dp))
            Text(
                note.text,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (warning) FontWeight.Bold else FontWeight.Normal,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (note.action != null) {
                TextButton(onClick = note.onAction, modifier = Modifier.heightIn(min = TouchTarget)) {
                    Text(note.action, fontWeight = FontWeight.Bold, color = if (warning) Color.Black else MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

private const val WATER_NEAR_M = 500.0

/** Closer than this, "from now"; nearer than this on the route, "here". */
private const val NOW_MS = 10 * 60_000L
private const val NEAR_AHEAD_M = 500.0

/** The last hours of daylight, when the time of sunset starts to matter. */
private const val SUNSET_SOON_MS = 2 * 3_600_000L

/** A caution, not an error. */
private val NoteAmber = Color(0xFFFFB300)
