package app.sentrek.home

import app.sentrek.ui.MapButton
import androidx.compose.animation.fadeOut
import androidx.compose.animation.fadeIn
import androidx.compose.animation.AnimatedVisibility
import app.sentrek.ui.SentrekIcons
import app.sentrek.maps.DownloadOutcome
import app.sentrek.maps.DownloadStatus
import app.sentrek.ui.mapButtonBorder
import app.sentrek.record.SignalWatch
import app.sentrek.geo.Geo
import androidx.compose.foundation.clickable
import kotlinx.coroutines.flow.Flow
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.location.Location
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.sentrek.R
import app.sentrek.ui.TouchTarget
import app.sentrek.data.PowerMode
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Box
import app.sentrek.geo.LatLon
import app.sentrek.record.GpsStatus
import app.sentrek.record.LocationRepository
import app.sentrek.record.formatCoordinates
import app.sentrek.ui.Format
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow

/** Amber: a warning that isn't an error. Dark text reads on it in both themes. */
private val Amber = Color(0xFFFFB300)

/** When the GPS label shows what, over time. Kept by the screen, so it survives the label being hidden. */
@Stable
class GpsLabelTiming {
    /** Searching for longer than a moment. */
    var showSearch by mutableStateOf(false)

    /** Searching for so long it's probably indoors. */
    var longSearch by mutableStateOf(false)

    /** A good fix just arrived. */
    var announceGood by mutableStateOf(false)

    /** The GPS is off, and the user is being told so for a moment. */
    var remindOff by mutableStateOf(false)

    internal var offReminders by mutableIntStateOf(0)

    /** Says again that the GPS is off (e.g. the location button was tapped). */
    fun remindOff() {
        offReminders++
    }
}

@Composable
fun rememberGpsLabelTiming(status: GpsStatus?): GpsLabelTiming {
    val timing = remember { GpsLabelTiming() }
    val searching = status is GpsStatus.Searching
    // Outdoors the first fix usually comes within seconds: don't flash "searching" at every start.
    // After half a minute it's most likely indoors: say so, and stop the spinner to spare the battery.
    LaunchedEffect(searching) {
        timing.showSearch = false
        timing.longSearch = false
        if (!searching) return@LaunchedEffect
        delay(SEARCH_GRACE_MS)
        timing.showSearch = true
        delay(SPIN_MS)
        timing.longSearch = true
    }
    val good = status is GpsStatus.Fix && !status.weak
    LaunchedEffect(good) {
        timing.announceGood = good
        if (good) {
            delay(READY_MS)
            timing.announceGood = false
        }
    }
    // A reminder, not a banner: it goes away by itself, and comes back at the next try.
    val off = status == GpsStatus.Off
    // Once per switch-off, not again each time the map screen comes back.
    LaunchedEffect(off, timing.offReminders) {
        if (!off) offReminded = false
        timing.remindOff = off && (timing.offReminders > 0 || !offReminded)
        if (timing.remindOff) {
            offReminded = true
            delay(OFF_REMINDER_MS)
            timing.remindOff = false
        }
    }
    return timing
}

/**
 * A label beside the location button saying what the GPS is doing, so a missing position
 * has a reason: switched off (for a moment, and again at each tap of the button), still
 * searching (and how many satellites it hears), or weak.
 * A good fix is announced briefly ([problemsOnly] skips that) and then the label goes away.
 */
@Composable
fun GpsIndicator(
    status: GpsStatus?,
    timing: GpsLabelTiming,
    onTurnOn: () -> Unit,
    modifier: Modifier = Modifier,
    problemsOnly: Boolean = false,
) {
    val showSearch = timing.showSearch
    val longSearch = timing.longSearch
    val announceGood = timing.announceGood && !problemsOnly

    val colors = MaterialTheme.colorScheme
    when {
        status == GpsStatus.Off && timing.remindOff -> Pill(
            stringResource(R.string.gps_off), colors.error, colors.onError, modifier, onClick = onTurnOn,
        )
        status is GpsStatus.Searching && showSearch -> Pill(
            text = when {
                longSearch -> stringResource(R.string.gps_no_signal)
                status.satellites == 0 -> stringResource(R.string.gps_searching)
                else -> pluralStringResource(R.plurals.gps_searching_satellites, status.satellites, status.satellites)
            },
            container = colors.surface,
            content = colors.onSurface,
            modifier = modifier,
            spinner = !longSearch,
        )
        status is GpsStatus.Fix && status.weak -> Pill(
            status.accuracy?.let { stringResource(R.string.gps_weak, Format.shortDistance(it.toDouble())) } ?: stringResource(R.string.gps_weak_unknown),
            Amber, Color.Black, modifier,
        )
        status is GpsStatus.Fix && announceGood -> Pill(
            stringResource(R.string.gps_ready, Format.shortDistance(status.accuracy?.toDouble() ?: 0.0)), colors.surface, colors.onSurface, modifier,
        )
    }
}

@Composable
private fun Pill(
    text: String,
    container: Color,
    content: Color,
    modifier: Modifier,
    spinner: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val body = @Composable {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (spinner) CircularProgressIndicator(Modifier.size(16.dp), color = LocalContentColor.current, strokeWidth = 2.dp)
            Text(text, style = MaterialTheme.typography.labelLarge, maxLines = 2)
        }
    }
    val shape = RoundedCornerShape(50)
    val sized = modifier.heightIn(min = TouchTarget)
    if (onClick != null) {
        Surface(onClick, sized, shape = shape, color = container, contentColor = content, shadowElevation = 4.dp, border = mapButtonBorder) {
            body()
        }
    } else {
        Surface(sized, shape = shape, color = container, contentColor = content, shadowElevation = 4.dp, border = mapButtonBorder) {
            body()
        }
    }
}

/**
 * Where the user is: coordinates, height and accuracy, plus speed and heading while moving,
 * following [fix] live. A tap copies the coordinates, e.g. to pass them on to rescuers.
 */
@Composable
fun CoordinatesLabel(fix: StateFlow<Location?>, barometer: Flow<HomeViewModel.Barometer?>, modifier: Modifier = Modifier) {
    val location by fix.collectAsStateWithLifecycle()
    // Asked for by opening this label: the sensor runs only while it's shown.
    val pressure by remember(barometer) { barometer }.collectAsStateWithLifecycle(initialValue = null)
    val here = location ?: return
    val context = LocalContext.current
    val coordinates = formatCoordinates(LatLon(here.latitude, here.longitude))
    val moving = here.hasSpeed() && here.speed >= MOVING_MPS
    val details = listOfNotNull(
        LocationRepository.altitude(here)?.let(Format::elevation),
        here.accuracy.takeIf { here.hasAccuracy() }?.let { "±" + Format.shortDistance(it.toDouble()) },
    ).joinToString(" · ")
    val motion = listOfNotNull(
        Format.speed(here.speed.toDouble()).takeIf { moving },
        here.bearing.takeIf { moving && here.hasBearing() }?.let { Format.bearing(it.toDouble()) },
    ).joinToString(" · ")
    Surface(
        onClick = {
            context.getSystemService(ClipboardManager::class.java)
                ?.setPrimaryClip(ClipData.newPlainText(coordinates, coordinates))
            // From Android 13 the system confirms copies itself.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                Toast.makeText(context, R.string.gps_copied, Toast.LENGTH_SHORT).show()
            }
        },
        modifier = modifier.heightIn(min = TouchTarget),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shadowElevation = 4.dp,
        border = mapButtonBorder,
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
            Text(coordinates, style = MaterialTheme.typography.titleMedium)
            if (details.isNotEmpty()) Text(details, style = MaterialTheme.typography.labelLarge)
            if (motion.isNotEmpty()) Text(motion, style = MaterialTheme.typography.labelLarge)
            pressure?.let { b ->
                val air = listOfNotNull(
                    b.altitude?.let { stringResource(R.string.gps_barometer_altitude, Format.elevation(it)) },
                    Format.pressure(b.hPa),
                ).joinToString(" · ")
                Text(air, style = MaterialTheme.typography.labelLarge)
            }
            sunsetToday(LatLon(here.latitude, here.longitude))?.let {
                Text(stringResource(R.string.sun_sunset, it), style = MaterialTheme.typography.labelLarge)
            }
            Text(stringResource(R.string.gps_copy_hint), style = MaterialTheme.typography.labelSmall)
        }
    }
}

/** The phone's location settings, to switch the GPS on. */
fun openLocationSettings(context: Context) {
    runCatching {
        context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

private const val SEARCH_GRACE_MS = 3_000L
private const val SPIN_MS = 30_000L
private const val READY_MS = 3_000L
private const val OFF_REMINDER_MS = 4_000L

/** The user was told the GPS is off since it last went off (for the whole app, not one screen). */
private var offReminded = false

/** How long the position stays after the finger leaves the location button. */
const val POSITION_LINGER_MS = 5_000L

/** Slower than this is standing still (GPS noise): no speed or heading. */
private const val MOVING_MPS = 0.5f

/**
 * A quiet note on the map, e.g. offline over an area that isn't downloaded. Small and
 * see-through, and it takes no touches: the map underneath works as ever.
 */
/** A word or two beside a map button, saying what it does. Not a button itself. */
@Composable
fun MapLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = modifier
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f), RoundedCornerShape(50))
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

/**
 * A map button whose [label] (what it does, or the mode it's in) shows only for a moment after
 * a long press on it: kept hidden, it would cover the map. The button says it to screen
 * readers all the same.
 */
@Composable
fun HintedMapButton(
    label: String,
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    active: Boolean = false,
) {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(shown) {
        if (shown) {
            delay(HINT_SHOWN_MS)
            shown = false
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        AnimatedVisibility(visible = shown, enter = fadeIn(), exit = fadeOut()) { MapLabel(label) }
        MapButton(
            icon = icon,
            contentDescription = contentDescription,
            onClick = onClick,
            active = active,
            onLongClick = { shown = true },
            longClickLabel = label,
        )
    }
}

private const val HINT_SHOWN_MS = 2_500L

/** A short note over the map. With [progress] (0 to 1), a ring filling up in place of the icon. */
@Composable
fun MapNote(text: String, icon: ImageVector, modifier: Modifier = Modifier, progress: Float? = null) {
    // A plain background, not a Surface: a Surface would swallow taps on the map under it.
    Row(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f), RoundedCornerShape(50))
            .padding(start = 10.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (progress != null) {
            CircularProgressIndicator(
                progress = { progress },
                modifier = Modifier.size(18.dp),
                strokeWidth = 3.dp,
                trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f),
                gapSize = 0.dp,
            )
        } else {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(18.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
    }
}

/**
 * No signal here: how far back the last one was, and when. A tap shows the way there. Quiet
 * like the other notes, it's there when it's needed and gone when the signal is back.
 */
@Composable
fun SignalNote(spot: SignalWatch.Spot, here: LatLon?, onClick: () -> Unit) {
    val time = android.text.format.DateFormat.getTimeFormat(LocalContext.current).format(java.util.Date(spot.time))
    val text = here?.let { stringResource(R.string.signal_note, Format.distance(Geo.distance(it, spot.position))) }
        ?: stringResource(R.string.signal_note_time, time)
    MapNote(
        text,
        SentrekIcons.Signal,
        Modifier
            .heightIn(min = TouchTarget)
            .clickable(onClickLabel = stringResource(R.string.signal_goto), onClick = onClick),
    )
}

/**
 * Battery saver or stamina mode on: a small round badge under the compass, as they change how
 * the whole app behaves. A tap opens Settings, where they're turned off.
 */
@Composable
fun PowerBadge(mode: PowerMode, onClick: () -> Unit) {
    val (icon, label) = when (mode) {
        PowerMode.Normal -> return
        PowerMode.Saver -> SentrekIcons.BatterySaver to R.string.saver_on_map
        PowerMode.Stamina -> SentrekIcons.BatteryAlert to R.string.stamina_on_map
    }
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.primary,
        shadowElevation = 4.dp,
        border = mapButtonBorder,
        modifier = Modifier.size(TouchTarget),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = stringResource(label))
        }
    }
}

/**
 * Maps downloading: one note with how far the whole lot is, or what it waits for. When it's
 * over, [OUTCOME_SHOWN_MS] of "downloaded" (or of what's missing). A tap opens Maps.
 */
@Composable
fun DownloadNotes(status: DownloadStatus?, outcome: Pair<Long, DownloadOutcome>?, onOpenMaps: () -> Unit) {
    val open = Modifier
        .heightIn(min = TouchTarget)
        .clickable(onClickLabel = stringResource(R.string.map_download_open), onClick = onOpenMaps)
    if (status != null) {
        val offline = status.waiting == DownloadStatus.Waiting.Network
        MapNote(
            if (offline) stringResource(R.string.map_download_offline) else stringResource(R.string.map_downloading, status.percent),
            SentrekIcons.CloudOff,
            open,
            progress = if (offline) null else status.fraction,
        )
        return
    }
    val (at, result) = outcome ?: return
    var shown by remember(at) { mutableStateOf(System.currentTimeMillis() - at < OUTCOME_SHOWN_MS) }
    LaunchedEffect(at) {
        delay((OUTCOME_SHOWN_MS - (System.currentTimeMillis() - at)).coerceAtLeast(0))
        shown = false
    }
    if (!shown) return
    if (result.failed > 0) {
        MapNote(pluralStringResource(R.plurals.map_download_failed, result.failed, result.failed), SentrekIcons.Download, open)
    } else {
        MapNote(stringResource(R.string.map_download_done), SentrekIcons.Check, open)
    }
}

private const val OUTCOME_SHOWN_MS = 8_000L
