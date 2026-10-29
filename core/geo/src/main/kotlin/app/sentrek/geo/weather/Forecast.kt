package app.sentrek.geo.weather

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.Reader
import java.util.Locale

/** A daily forecast for one place, from Open-Meteo (open-meteo.com, no key, CC BY 4.0). */
data class Forecast(
    val days: List<Day>,
    /** The height the forecast is for, in metres: the place's own when we knew it. */
    val elevation: Double?,
) {
    data class Day(
        /** Local date at the place, "2026-10-29". */
        val date: String,
        val sky: Sky,
        val maxTemp: Double?,
        val minTemp: Double?,
        /** Rain and snow, in mm of water. */
        val precipitation: Double?,
        /** Chance of any precipitation, 0–100. */
        val precipitationChance: Int?,
        /** In m/s. */
        val wind: Double?,
        val gusts: Double?,
    ) {
        /** Thunderstorms or gusts that make ridges dangerous. */
        val storm get() = sky == Sky.Storm
        val windy get() = (gusts ?: wind ?: 0.0) >= STRONG_GUSTS_MS
    }

    /** WMO weather codes, grouped into what a hiker cares about. */
    enum class Sky {
        Clear, PartlyCloudy, Cloudy, Fog, Drizzle, Rain, Snow, Showers, Storm;

        companion object {
            fun of(code: Int): Sky = when (code) {
                0, 1 -> Clear
                2 -> PartlyCloudy
                3 -> Cloudy
                45, 48 -> Fog
                in 51..57 -> Drizzle
                in 61..67 -> Rain
                in 71..77, 85, 86 -> Snow
                in 80..82 -> Showers
                in 95..99 -> Storm
                else -> Cloudy
            }
        }
    }

    companion object {
        /** About 60 km/h: hard to walk upright on an exposed ridge. */
        const val STRONG_GUSTS_MS = 16.7
        const val DAYS = 7
        private val DAILY = listOf(
            "weather_code", "temperature_2m_max", "temperature_2m_min", "precipitation_sum",
            "precipitation_probability_max", "wind_speed_10m_max", "wind_gusts_10m_max",
        )

        /**
         * The request for [lat]/[lon], rounded to about a kilometre: forecasts aren't finer
         * than that, the HTTP cache then serves places close by, and we say no more than needed
         * about where the user is. With [elevation], temperatures are for that height.
         */
        fun url(lat: Double, lon: Double, elevation: Double?): String = buildString {
            append("https://api.open-meteo.com/v1/forecast")
            append(String.format(Locale.ROOT, "?latitude=%.2f&longitude=%.2f", lat, lon))
            elevation?.let { append(String.format(Locale.ROOT, "&elevation=%.0f", Math.round(it / 10.0) * 10.0)) }
            append("&daily=").append(DAILY.joinToString(","))
            append("&timezone=auto&wind_speed_unit=ms&forecast_days=$DAYS")
        }

        /** The days in an Open-Meteo answer; null when it has none. */
        fun parse(reader: Reader): Forecast? {
            val root = runCatching { JsonParser.parseReader(reader).asJsonObject }.getOrNull() ?: return null
            val daily = root.getAsJsonObject("daily") ?: return null
            val dates = daily.getAsJsonArray("time") ?: return null
            fun number(key: String, i: Int) = daily.array(key)?.takeIf { i < it.size() }?.get(i)
                ?.takeUnless { it.isJsonNull }?.asDouble
            val days = (0 until dates.size()).map { i ->
                Day(
                    date = dates[i].asString,
                    sky = Sky.of(number("weather_code", i)?.toInt() ?: -1),
                    maxTemp = number("temperature_2m_max", i),
                    minTemp = number("temperature_2m_min", i),
                    precipitation = number("precipitation_sum", i),
                    precipitationChance = number("precipitation_probability_max", i)?.toInt(),
                    wind = number("wind_speed_10m_max", i),
                    gusts = number("wind_gusts_10m_max", i),
                )
            }
            if (days.isEmpty()) return null
            val elevation = root.get("elevation")?.takeUnless { it.isJsonNull }?.asDouble
            return Forecast(days, elevation)
        }

        private fun JsonObject.array(key: String): JsonArray? = get(key)?.takeIf { it.isJsonArray }?.asJsonArray
    }
}
