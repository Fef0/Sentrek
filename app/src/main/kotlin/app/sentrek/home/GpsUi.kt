package app.sentrek.home

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.location.Location
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.sentrek.R
import app.sentrek.geo.LatLon
import app.sentrek.record.GpsStatus
import app.sentrek.record.LocationRepository
import app.sentrek.record.formatCoordinates
import app.sentrek.ui.Format
import app.sentrek.ui.TouchTarget
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.roundToInt

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
    return timing
}

/**
 * A label beside the location button saying what the GPS is doing, so a missing position
 * has a reason: switched off, still searching (and how many satellites it hears), or weak.
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
        status == GpsStatus.Off -> Pill(
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
            status.accuracy?.let { stringResource(R.string.gps_weak, it.roundToInt()) } ?: stringResource(R.string.gps_weak_unknown),
            Amber, Color.Black, modifier,
        )
        status is GpsStatus.Fix && announceGood -> Pill(
            stringResource(R.string.gps_ready, status.accuracy?.roundToInt() ?: 0), colors.surface, colors.onSurface, modifier,
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
        Surface(onClick, sized, shape = shape, color = container, contentColor = content, shadowElevation = 4.dp) {
            body()
        }
    } else {
        Surface(sized, shape = shape, color = container, contentColor = content, shadowElevation = 4.dp) {
            body()
        }
    }
}

/**
 * Where the user is: coordinates, height and accuracy, following [fix] live. A tap copies the
 * coordinates, e.g. to pass them on to rescuers.
 */
@Composable
fun CoordinatesLabel(fix: StateFlow<Location?>, modifier: Modifier = Modifier) {
    val location by fix.collectAsStateWithLifecycle()
    val here = location ?: return
    val context = LocalContext.current
    val coordinates = formatCoordinates(LatLon(here.latitude, here.longitude))
    val details = listOfNotNull(
        LocationRepository.altitude(here)?.let(Format::meters),
        here.accuracy.takeIf { here.hasAccuracy() }?.let { "±${it.roundToInt()} m" },
        stringResource(R.string.gps_copy_hint),
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
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
            Text(coordinates, style = MaterialTheme.typography.titleMedium)
            Text(details, style = MaterialTheme.typography.labelMedium)
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

/** How long the coordinates stay after a tap on the location button. */
const val COORDINATES_MS = 15_000L
