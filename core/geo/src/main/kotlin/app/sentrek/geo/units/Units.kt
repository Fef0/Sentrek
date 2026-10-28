package app.sentrek.geo.units

import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.tan

enum class DistanceUnit { Kilometers, Miles, NauticalMiles }
enum class ElevationUnit { Meters, Feet }
enum class SpeedUnit { KilometersPerHour, MilesPerHour, Knots }
enum class SlopeUnit { Degrees, Percent }
enum class BearingUnit { Degrees, Mils }
enum class CoordinateFormat { Decimal, DegreesMinutes, DegreesMinutesSeconds }

/** How numbers are shown: each quantity has its own unit, as the user chose in Settings. */
data class Units(
    val distance: DistanceUnit = DistanceUnit.Kilometers,
    val elevation: ElevationUnit = ElevationUnit.Meters,
    val speed: SpeedUnit = SpeedUnit.KilometersPerHour,
    val slope: SlopeUnit = SlopeUnit.Degrees,
    val bearing: BearingUnit = BearingUnit.Degrees,
    val coordinates: CoordinateFormat = CoordinateFormat.Decimal,
) {
    companion object {
        val Metric = Units()
        val Imperial = Units(DistanceUnit.Miles, ElevationUnit.Feet, SpeedUnit.MilesPerHour)

        /** Imperial where it's the everyday system (US, Liberia, Myanmar), metric elsewhere. */
        fun forCountry(country: String): Units = if (country.uppercase(Locale.ROOT) in IMPERIAL_COUNTRIES) Imperial else Metric

        private val IMPERIAL_COUNTRIES = setOf("US", "LR", "MM")
    }
}

/** Glanceable numbers in a given unit: few digits, unit always shown. */
object UnitFormat {
    const val FOOT_M = 0.3048
    const val MILE_M = 1_609.344
    const val NAUTICAL_MILE_M = 1_852.0

    /** "850 m", "3.42 km", "12.5 km"; "530 ft", "2.13 mi"; "1.85 nmi". */
    fun distance(meters: Double, unit: DistanceUnit, locale: Locale = Locale.getDefault()): String = when (unit) {
        DistanceUnit.Kilometers -> when {
            meters < 1_000 -> "${meters.roundToInt()} m"
            else -> decimal(meters / 1_000, "km", locale)
        }
        DistanceUnit.Miles -> when {
            // Under a tenth of a mile, feet: "0.04 mi" means nothing on the trail.
            meters < MILE_M / 10 -> "${(meters / FOOT_M).roundToInt()} ft"
            else -> decimal(meters / MILE_M, "mi", locale)
        }
        DistanceUnit.NauticalMiles -> when {
            meters < NAUTICAL_MILE_M / 10 -> "${meters.roundToInt()} m"
            else -> decimal(meters / NAUTICAL_MILE_M, "nmi", locale)
        }
    }

    /** A short distance (GPS accuracy): metres, or feet with miles. */
    fun shortDistance(meters: Double, unit: DistanceUnit): String =
        if (unit == DistanceUnit.Miles) "${(meters / FOOT_M).roundToInt()} ft" else "${meters.roundToInt()} m"

    /** Height, climb or descent: "1520 m" or "4987 ft". */
    fun elevation(meters: Double, unit: ElevationUnit): String = when (unit) {
        ElevationUnit.Meters -> "${meters.roundToInt()} m"
        ElevationUnit.Feet -> "${(meters / FOOT_M).roundToInt()} ft"
    }

    /** "4.5 km/h", "12 km/h": one decimal only at walking pace. */
    fun speed(metersPerSecond: Double, unit: SpeedUnit, locale: Locale = Locale.getDefault()): String {
        val (value, label) = when (unit) {
            SpeedUnit.KilometersPerHour -> metersPerSecond * 3.6 to "km/h"
            SpeedUnit.MilesPerHour -> metersPerSecond * 3_600 / MILE_M to "mph"
            SpeedUnit.Knots -> metersPerSecond * 3_600 / NAUTICAL_MILE_M to "kn"
        }
        return if (value < 10) String.format(locale, "%.1f %s", value, label) else "${value.roundToInt()} $label"
    }

    /** "↗ 12°", "↘ 8°" or "→ 0°" (or "↗ 21%"): direction and steepness at a glance. */
    fun slope(degrees: Double, unit: SlopeUnit): String {
        val (rounded, suffix) = when (unit) {
            SlopeUnit.Degrees -> degrees.roundToInt() to "°"
            // Steeper than 89° is a wall: the percentage would just run off to infinity.
            SlopeUnit.Percent -> (tan(Math.toRadians(degrees.coerceIn(-89.0, 89.0))) * 100).roundToInt() to "%"
        }
        return when {
            rounded > 0 -> "↗ $rounded$suffix"
            rounded < 0 -> "↘ ${-rounded}$suffix"
            else -> "→ 0$suffix"
        }
    }

    /** Direction of travel: "SW 225°", or "SW 4000 mil" (NATO mils, 6400 to the circle). */
    fun bearing(degrees: Double, unit: BearingUnit): String {
        val d = ((degrees % 360) + 360) % 360
        val compass = COMPASS[((d + 22.5) / 45).toInt() % 8]
        return when (unit) {
            BearingUnit.Degrees -> "$compass ${d.roundToInt() % 360}°"
            BearingUnit.Mils -> "$compass ${(d * 6_400 / 360).roundToInt() % 6_400} mil"
        }
    }

    /**
     * "37.75000, 15.00000"; "37° 45.000′ N, 15° 00.000′ E"; "37° 45′ 00.0″ N, 15° 00′ 00.0″ E".
     * Each about a metre of precision.
     */
    fun coordinates(lat: Double, lon: Double, format: CoordinateFormat): String = when (format) {
        CoordinateFormat.Decimal -> String.format(Locale.ROOT, "%.5f, %.5f", lat, lon)
        CoordinateFormat.DegreesMinutes -> "${degreesMinutes(lat, 'N', 'S')}, ${degreesMinutes(lon, 'E', 'W')}"
        CoordinateFormat.DegreesMinutesSeconds -> "${degreesMinutesSeconds(lat, 'N', 'S')}, ${degreesMinutesSeconds(lon, 'E', 'W')}"
    }

    private fun degreesMinutes(value: Double, positive: Char, negative: Char): String {
        // Rounded as a whole, so 59.9999′ becomes the next degree, never "60.000′".
        val thousandths = (abs(value) * 60_000).roundToLong()
        val degrees = thousandths / 60_000
        val minutes = thousandths % 60_000
        return String.format(Locale.ROOT, "%d° %02d.%03d′ %c", degrees, minutes / 1_000, minutes % 1_000, if (value < 0) negative else positive)
    }

    private fun degreesMinutesSeconds(value: Double, positive: Char, negative: Char): String {
        val tenths = (abs(value) * 36_000).roundToLong()
        val degrees = tenths / 36_000
        val minutes = tenths % 36_000 / 600
        val seconds = tenths % 600
        return String.format(Locale.ROOT, "%d° %02d′ %02d.%d″ %c", degrees, minutes, seconds / 10, seconds % 10, if (value < 0) negative else positive)
    }

    private fun decimal(value: Double, label: String, locale: Locale): String =
        if (value < 10) String.format(locale, "%.2f %s", value, label) else String.format(locale, "%.1f %s", value, label)

    private val COMPASS = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")

    /** The largest 1, 2 or 5 × 10ⁿ not above [max] (for a scale bar). */
    fun roundDown(max: Double): Double {
        if (max <= 0) return 0.0
        val power = Math.pow(10.0, floor(Math.log10(max)))
        return listOf(5.0, 2.0, 1.0).map { it * power }.first { it <= max * (1 + 1e-9) }
    }
}
