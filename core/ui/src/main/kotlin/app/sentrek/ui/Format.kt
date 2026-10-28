package app.sentrek.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.sentrek.geo.units.UnitFormat
import app.sentrek.geo.units.Units
import java.util.Locale

/** Glanceable numbers: few digits, units always shown, in the units chosen in Settings. */
object Format {
    /**
     * Set at startup and whenever the setting changes (SentrekApp). Compose state, so anything
     * drawn with these functions redraws in the new units.
     */
    var units by mutableStateOf(Units.forCountry(Locale.getDefault().country))

    fun distance(meters: Double): String = UnitFormat.distance(meters, units.distance)

    /** GPS accuracy and the like: metres, or feet with miles. */
    fun shortDistance(meters: Double): String = UnitFormat.shortDistance(meters, units.distance)

    /** Height, climb or descent. */
    fun elevation(meters: Double): String = UnitFormat.elevation(meters, units.elevation)

    fun speed(metersPerSecond: Double): String = UnitFormat.speed(metersPerSecond, units.speed)

    fun slope(degrees: Double): String = UnitFormat.slope(degrees, units.slope)

    fun bearing(degrees: Double): String = UnitFormat.bearing(degrees, units.bearing)

    fun coordinates(lat: Double, lon: Double): String = UnitFormat.coordinates(lat, lon, units.coordinates)

    /** 1:05:09, or 5:09 under an hour. */
    fun duration(millis: Long): String {
        val total = (millis / 1_000).coerceAtLeast(0)
        val h = total / 3_600
        val m = total % 3_600 / 60
        val s = total % 60
        return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s) else String.format(Locale.ROOT, "%d:%02d", m, s)
    }

    /** An estimated walking time, to the minute: "≈ 3:25 h". Seconds would be false precision. */
    fun estimate(millis: Long): String {
        val minutes = ((millis + 30_000) / 60_000).coerceAtLeast(0)
        return String.format(Locale.ROOT, "≈ %d:%02d h", minutes / 60, minutes % 60)
    }
}
