package app.sentrek.record

import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.ui.window.Dialog
import androidx.compose.material3.Icon
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.sentrek.feature.record.R
import app.sentrek.geo.units.DistanceUnit
import app.sentrek.geo.weather.Forecast
import app.sentrek.ui.Format
import app.sentrek.ui.TouchTarget
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * A "Weather forecast" button that turns into the week's forecast once tapped: nothing is
 * fetched until the user asks. [highest] says the forecast is for a route's highest point.
 */
@Composable
fun WeatherSection(load: suspend () -> WeatherResult, highest: Boolean = false) {
    // Null before the first tap; loading while [busy].
    var result by remember { mutableStateOf<WeatherResult?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val fetch: () -> Unit = {
        busy = true
        scope.launch {
            result = load()
            busy = false
        }
    }
    val wide = Modifier.fillMaxWidth().heightIn(min = TouchTarget)
    val shown = result as? WeatherResult.Shown
    when {
        busy -> OutlinedButton(onClick = {}, enabled = false, modifier = wide) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            Text(stringResource(R.string.weather_loading), Modifier.padding(start = 12.dp))
        }
        shown != null -> ForecastDays(shown.weather, highest, onRetry = fetch)
        result == null -> OutlinedButton(onClick = fetch, modifier = wide) { Text(stringResource(R.string.weather_get)) }
        else -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(if (result == WeatherResult.Offline) R.string.weather_offline else R.string.weather_failed),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.error,
            )
            OutlinedButton(onClick = fetch, modifier = wide) { Text(stringResource(R.string.weather_retry)) }
        }
    }
}

@Composable
private fun ForecastDays(weather: Weather, highest: Boolean, onRetry: () -> Unit) {
    val forecast = weather.forecast
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        HorizontalDivider()
        val height = forecast.elevation?.let(Format::elevation)
        Text(
            when {
                height == null -> stringResource(R.string.weather_title)
                highest -> stringResource(R.string.weather_at_highest, height)
                else -> stringResource(R.string.weather_at, height)
            },
            style = MaterialTheme.typography.titleSmall,
        )
        if (weather.offline) {
            val at = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(weather.fetchedAt))
            Text(
                stringResource(R.string.weather_saved, at),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.error,
            )
        }
        weather.avalanche?.let { AvalancheCard(it) }
        // Offline, the forecast says so already.
        if (weather.avalancheFailed && !weather.offline) {
            Text(
                stringResource(R.string.avalanche_failed),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
        val today = remember { ISO.format(Date()) }
        // A forecast saved days ago starts in the past: those days are over.
        val days = forecast.days.filterIndexed { i, day -> day.date >= today || (i == 0 && !weather.offline) }
        var open by remember { mutableStateOf<Pair<Forecast.Day, String>?>(null) }
        for ((i, day) in days.withIndex()) {
            val label = dayLabel(day.date, today, i)
            // Hour by hour, for forecasts that have the hours (not those saved before).
            val hours = forecast.hoursOf(day.date).isNotEmpty()
            DayRow(day, label, onClick = if (hours) ({ open = day to label }) else null)
        }
        open?.let { (day, label) ->
            HoursDialog(label, forecast.hoursOf(day.date), today = day.date == today && !weather.offline) { open = null }
        }
        if (weather.offline) {
            OutlinedButton(onClick = onRetry, modifier = Modifier.fillMaxWidth().heightIn(min = TouchTarget)) {
                Text(stringResource(R.string.weather_retry))
            }
        }
        Text(
            stringResource(R.string.weather_credit),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun DayRow(day: Forecast.Day, label: String, onClick: (() -> Unit)?) {
    Row(
        modifier = if (onClick != null) {
            Modifier.fillMaxWidth().heightIn(min = TouchTarget).clickable(onClickLabel = stringResource(R.string.weather_hours), onClick = onClick)
        } else {
            Modifier.fillMaxWidth()
        },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.width(76.dp))
        Text(SKY_SYMBOL.getValue(day.sky), fontSize = 28.sp)
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    listOfNotNull(day.maxTemp, day.minTemp).joinToString(" / ") { temperature(it) },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text("  " + stringResource(SKY_LABEL.getValue(day.sky)), style = MaterialTheme.typography.bodyLarge)
            }
            val wet = (day.precipitation ?: 0.0) >= 0.1 || (day.precipitationChance ?: 0) >= 20
            val details = listOfNotNull(
                listOfNotNull(day.precipitation?.takeIf { it >= 0.05 }?.let(::precipitation), day.precipitationChance?.let { "$it%" })
                    .takeIf { wet && it.isNotEmpty() }?.joinToString(" ")?.let { "💧 $it" },
                day.wind?.let { "💨 " + Format.speed(it) },
            )
            if (details.isNotEmpty()) Text(details.joinToString("   "), style = MaterialTheme.typography.bodyMedium)
            val warnings = listOfNotNull(
                stringResource(R.string.weather_storm).takeIf { day.storm },
                day.gusts?.takeIf { day.windy }?.let { stringResource(R.string.weather_gusts, Format.speed(it)) },
            )
            if (warnings.isNotEmpty()) {
                Text(
                    "⚠ " + warnings.joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        // "There's more": the hours of the day.
        if (onClick != null) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
    }
}

/** The [hours] of one day; [today] starts from the hour now, the past ones are no use. */
@Composable
private fun HoursDialog(label: String, hours: List<Forecast.Hour>, today: Boolean, onDismiss: () -> Unit) {
    val now = remember { java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY) }
    val shown = if (today) hours.filter { it.hour >= now } else hours
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.padding(vertical = 16.dp)) {
                Text(
                    stringResource(R.string.weather_hours_title, label),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                )
                LazyColumn(Modifier.weight(1f, fill = false), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp)) {
                    items(shown) { HourRow(it) }
                }
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.align(Alignment.End).padding(end = 12.dp).heightIn(min = TouchTarget),
                ) { Text(stringResource(R.string.weather_hours_close)) }
            }
        }
    }
}

@Composable
private fun HourRow(hour: Forecast.Hour) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(String.format(Locale.ROOT, "%02d", hour.hour), style = MaterialTheme.typography.titleMedium, modifier = Modifier.width(28.dp))
        // No sun at night.
        val symbol = when {
            hour.day != false -> SKY_SYMBOL.getValue(hour.sky)
            hour.sky == Forecast.Sky.Clear -> "🌙"
            hour.sky == Forecast.Sky.PartlyCloudy -> "☁️"
            else -> SKY_SYMBOL.getValue(hour.sky)
        }
        Text(symbol, fontSize = 24.sp)
        Text(
            hour.temp?.let(::temperature).orEmpty(),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.width(44.dp),
        )
        Column(Modifier.weight(1f)) {
            val wet = (hour.precipitation ?: 0.0) >= 0.1 || (hour.precipitationChance ?: 0) >= 20
            val rain = listOfNotNull(hour.precipitation?.takeIf { it >= 0.05 }?.let(::precipitation), hour.precipitationChance?.let { "$it%" })
                .takeIf { wet && it.isNotEmpty() }?.joinToString(" ")?.let { "💧 $it" }
            val wind = hour.wind?.let { w ->
                "💨 " + Format.speed(w) + (hour.gusts?.takeIf { it >= Forecast.STRONG_GUSTS_MS }?.let { " (" + Format.speed(it) + ")" } ?: "")
            }
            Text(listOfNotNull(rain, wind).joinToString("   "), style = MaterialTheme.typography.bodyMedium)
            if (hour.sky == Forecast.Sky.Storm || (hour.gusts ?: 0.0) >= Forecast.STRONG_GUSTS_MS) {
                Text(
                    "⚠ " + stringResource(if (hour.sky == Forecast.Sky.Storm) R.string.weather_storm else R.string.weather_windy),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun dayLabel(date: String, today: String, index: Int): String = when {
    // The place may be in another time zone: its first day is still its today.
    date == today || (index == 0 && date < today) -> stringResource(R.string.weather_today)
    else -> runCatching { ISO.parse(date) }.getOrNull()?.let { DAY.format(it) } ?: date
}

/** °F with miles, as in the US; °C otherwise. */
private fun temperature(celsius: Double): String =
    if (Format.units.distance == DistanceUnit.Miles) "${(celsius * 9 / 5 + 32).roundToInt()}°" else "${celsius.roundToInt()}°"

private fun precipitation(mm: Double): String =
    if (Format.units.distance == DistanceUnit.Miles) String.format(Locale.getDefault(), "%.1f in", mm / 25.4)
    else if (mm < 1) String.format(Locale.getDefault(), "%.1f mm", mm) else "${mm.roundToInt()} mm"

private val ISO = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT)
private val DAY = SimpleDateFormat("EEE d", Locale.getDefault())

private val SKY_SYMBOL = mapOf(
    Forecast.Sky.Clear to "☀️",
    Forecast.Sky.PartlyCloudy to "⛅",
    Forecast.Sky.Cloudy to "☁️",
    Forecast.Sky.Fog to "🌫️",
    Forecast.Sky.Drizzle to "🌦️",
    Forecast.Sky.Rain to "🌧️",
    Forecast.Sky.Snow to "❄️",
    Forecast.Sky.Showers to "🌦️",
    Forecast.Sky.Storm to "⛈️",
)

private val SKY_LABEL = mapOf(
    Forecast.Sky.Clear to R.string.weather_sky_clear,
    Forecast.Sky.PartlyCloudy to R.string.weather_sky_partly_cloudy,
    Forecast.Sky.Cloudy to R.string.weather_sky_cloudy,
    Forecast.Sky.Fog to R.string.weather_sky_fog,
    Forecast.Sky.Drizzle to R.string.weather_sky_drizzle,
    Forecast.Sky.Rain to R.string.weather_sky_rain,
    Forecast.Sky.Snow to R.string.weather_sky_snow,
    Forecast.Sky.Showers to R.string.weather_sky_showers,
    Forecast.Sky.Storm to R.string.weather_sky_storm,
)
