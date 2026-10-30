package app.sentrek.geo

import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.tan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToLong
import kotlin.math.sin

/**
 * Sunrise and sunset, worked out on the phone (the sunrise equation, within a minute or two
 * outside the polar regions): no network needed on the mountain.
 */
object Sun {
    /** The sun's centre this far below the horizon at sunset (refraction and its radius). */
    private const val SUNSET_ALTITUDE = -0.833

    /** Sunrise and sunset in epoch millis; null at a time of polar day or night. */
    data class Day(val sunrise: Long, val sunset: Long)

    /** The day (local to [lon]) that [time] falls in. */
    fun day(time: Long, lat: Double, lon: Double): Day? {
        // Days since J2000 at the local solar noon nearest [time].
        val julian = time / MILLIS_PER_DAY.toDouble() + UNIX_EPOCH_JD
        val n = floor(julian - J2000 + lon / 360 + 0.5)
        val meanSolarNoon = n - lon / 360
        val anomaly = Math.toRadians((357.5291 + 0.98560028 * meanSolarNoon).mod(360.0))
        val centre = 1.9148 * sin(anomaly) + 0.02 * sin(2 * anomaly) + 0.0003 * sin(3 * anomaly)
        val longitude = Math.toRadians((Math.toDegrees(anomaly) + centre + 180 + 102.9372).mod(360.0))
        val transit = J2000 + meanSolarNoon + 0.0053 * sin(anomaly) - 0.0069 * sin(2 * longitude)
        val declination = asin(sin(longitude) * sin(Math.toRadians(23.4397)))
        val phi = Math.toRadians(lat)
        val cosHourAngle = (sin(Math.toRadians(SUNSET_ALTITUDE)) - sin(phi) * sin(declination)) / (cos(phi) * cos(declination))
        if (cosHourAngle !in -1.0..1.0) return null
        val half = Math.toDegrees(acos(cosHourAngle)) / 360
        return Day(toMillis(transit - half), toMillis(transit + half))
    }

    /** Where the sun is in the sky: compass [azimuth] and [altitude] above the horizon, in degrees. */
    data class Position(val azimuth: Double, val altitude: Double)

    /** The sun seen from [lat], [lon] at [time] (the low-precision solar ephemeris: a few hundredths of a degree). */
    fun position(time: Long, lat: Double, lon: Double): Position {
        val d = time / MILLIS_PER_DAY.toDouble() + UNIX_EPOCH_JD - J2000
        val anomaly = Math.toRadians((357.529 + 0.98560028 * d).mod(360.0))
        val mean = 280.459 + 0.98564736 * d
        val longitude = Math.toRadians((mean + 1.915 * sin(anomaly) + 0.020 * sin(2 * anomaly)).mod(360.0))
        val obliquity = Math.toRadians(23.439 - 0.00000036 * d)
        val rightAscension = atan2(cos(obliquity) * sin(longitude), cos(longitude))
        val declination = asin(sin(obliquity) * sin(longitude))
        val siderealDeg = (280.46061837 + 360.98564736629 * d + lon).mod(360.0)
        val hourAngle = Math.toRadians(siderealDeg) - rightAscension
        val phi = Math.toRadians(lat)
        val altitude = asin(sin(phi) * sin(declination) + cos(phi) * cos(declination) * cos(hourAngle))
        val azimuth = atan2(-sin(hourAngle), tan(declination) * cos(phi) - sin(phi) * cos(hourAngle))
        return Position((Math.toDegrees(azimuth) + 360) % 360, Math.toDegrees(altitude))
    }

    private fun toMillis(julian: Double): Long = ((julian - UNIX_EPOCH_JD) * MILLIS_PER_DAY).roundToLong()

    private const val J2000 = 2_451_545.0
    private const val UNIX_EPOCH_JD = 2_440_587.5
    private const val MILLIS_PER_DAY = 86_400_000L
}
