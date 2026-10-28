package app.sentrek.geo

import kotlin.math.acos
import kotlin.math.asin
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

    private fun toMillis(julian: Double): Long = ((julian - UNIX_EPOCH_JD) * MILLIS_PER_DAY).roundToLong()

    private const val J2000 = 2_451_545.0
    private const val UNIX_EPOCH_JD = 2_440_587.5
    private const val MILLIS_PER_DAY = 86_400_000L
}
