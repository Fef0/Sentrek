package app.sentrek.geo.weather

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.Reader
import java.util.Locale

/** An hour-by-hour forecast for one place, from Open-Meteo, for what's coming on a walk. */
data class Hourly(val hours: List<Hour>) {
    data class Hour(
        /** Start of the hour, UTC milliseconds. */
        val time: Long,
        /** Chance of any precipitation, 0–100. */
        val chance: Int?,
        /** Rain and snow, in mm of water. */
        val mm: Double?,
        val sky: Forecast.Sky,
    ) {
        /** What the hiker gets, if anything: storms whatever the odds say, else likely rain or snow. */
        val wet: Wet?
            get() = when {
                sky == Forecast.Sky.Storm -> Wet.Storm
                (chance ?: 0) < LIKELY || (mm ?: 0.0) < WET_MM -> null
                sky == Forecast.Sky.Snow -> Wet.Snow
                else -> Wet.Rain
            }
    }

    enum class Wet { Rain, Snow, Storm }

    /** The hour [timeMs] falls in, or null outside the forecast. */
    fun at(timeMs: Long): Hour? = hours.lastOrNull { it.time <= timeMs }?.takeIf { timeMs < it.time + HOUR_MS }

    companion object {
        const val HOUR_MS = 3_600_000L
        const val LIKELY = 50
        const val WET_MM = 0.2

        /**
         * One request for all [points] (rounded to about a kilometre, as [Forecast.url]), the
         * next [days] hour by hour, times as UTC seconds.
         */
        fun url(points: List<Pair<Double, Double>>, days: Int = 2): String = buildString {
            append("https://api.open-meteo.com/v1/forecast")
            append("?latitude=").append(points.joinToString(",") { String.format(Locale.ROOT, "%.2f", it.first) })
            append("&longitude=").append(points.joinToString(",") { String.format(Locale.ROOT, "%.2f", it.second) })
            append("&hourly=precipitation_probability,precipitation,weather_code")
            append("&timeformat=unixtime&forecast_days=$days")
        }

        /** One forecast per place asked for, in order; null when the answer doesn't fit. */
        fun parse(reader: Reader): List<Hourly>? {
            val root = runCatching { JsonParser.parseReader(reader) }.getOrNull() ?: return null
            // One place: an object; several: an array of them.
            val places = when {
                root.isJsonArray -> root.asJsonArray.map { it }
                root.isJsonObject -> listOf(root)
                else -> return null
            }
            return places.map { one(it) ?: return null }
        }

        private fun one(element: JsonElement): Hourly? {
            val hourly = (element as? JsonObject)?.getAsJsonObject("hourly") ?: return null
            val times = hourly.getAsJsonArray("time") ?: return null
            fun number(key: String, i: Int) = hourly.get(key)?.takeIf { it.isJsonArray }?.asJsonArray
                ?.takeIf { i < it.size() }?.get(i)?.takeUnless { it.isJsonNull }?.asDouble
            return Hourly(
                (0 until times.size()).map { i ->
                    Hour(
                        time = times[i].asLong * 1000,
                        chance = number("precipitation_probability", i)?.toInt(),
                        mm = number("precipitation", i),
                        sky = Forecast.Sky.of(number("weather_code", i)?.toInt() ?: -1),
                    )
                },
            )
        }
    }
}

/**
 * The first place on the way where it's likely to be wet, at the time the user gets there:
 * [Wet.Storm] wins over rain further on only if it comes first.
 */
object WetAhead {
    /** A point of the way ahead: [ahead] metres on, reached at [eta], with its forecast. */
    data class Sample(val ahead: Double, val eta: Long, val elevation: Double?, val forecast: Hourly)

    /** Where and when it gets wet; [from] is when the user is there, or now if it's already on. */
    data class Found(val wet: Hourly.Wet, val from: Long, val ahead: Double, val elevation: Double?)

    fun first(samples: List<Sample>, now: Long): Found? {
        for (s in samples.sortedBy { it.ahead }) {
            val wet = s.forecast.at(s.eta)?.wet ?: continue
            return Found(wet, maxOf(now, s.eta), s.ahead, s.elevation)
        }
        return null
    }

    /** Without a route: the first wet hour here within [hours]. */
    fun here(forecast: Hourly, now: Long, hours: Int = 3): Found? {
        for (h in 0..hours) {
            val time = now + h * Hourly.HOUR_MS
            val wet = forecast.at(time)?.wet ?: continue
            return Found(wet, maxOf(now, forecast.at(time)!!.time), 0.0, null)
        }
        return null
    }
}
