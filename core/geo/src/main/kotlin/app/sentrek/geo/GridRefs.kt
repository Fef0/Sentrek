package app.sentrek.geo

import java.util.Locale
import kotlin.math.floor
import kotlin.math.roundToLong

/**
 * The position as rescue services may ask for it: UTM (maps, mountain rescue) and Plus Code
 * (short, readable over a bad line). Both offline.
 */
object GridRefs {
    /** "32T 500000 4982950": zone and band, easting, northing in metres. Null outside 84°N–80°S. */
    fun utm(p: LatLon): String? {
        if (p.lat < -80.0 || p.lat > 84.0) return null
        val zone = utmZone(p.lat, p.lon)
        val band = "CDEFGHJKLMNPQRSTUVWX"[((p.lat + 80) / 8).toInt().coerceIn(0, 19)]
        val (easting, north) = Utm.forward(p, zone)
        var northing = north
        if (p.lat < 0) northing += 10_000_000
        return String.format(Locale.ROOT, "%d%c %d %d", zone, band, easting.roundToLong(), northing.roundToLong())
    }

    /** Ten-digit Open Location Code, about 14 m: "8FVC9G8F+6X". */
    fun plusCode(p: LatLon): String {
        // Whole steps of the finest place (1/8000°), so no floating-point drift between digits.
        var lat = floor((p.lat + 90) * PLUS_STEPS + 1e-6).toLong().coerceIn(0, 180L * PLUS_STEPS - 1)
        var lon = floor((((p.lon + 180) % 360 + 360) % 360) * PLUS_STEPS + 1e-6).toLong()
        val latDigits = CharArray(5)
        val lonDigits = CharArray(5)
        for (i in 4 downTo 0) {
            latDigits[i] = PLUS_DIGITS[(lat % 20).toInt()]
            lonDigits[i] = PLUS_DIGITS[(lon % 20).toInt()]
            lat /= 20
            lon /= 20
        }
        val code = StringBuilder()
        for (i in 0 until 5) {
            if (i == 4) code.append('+')
            code.append(latDigits[i]).append(lonDigits[i])
        }
        return code.toString()
    }

    /** Zones are 6° wide, with the exceptions of southern Norway and Svalbard. */
    private fun utmZone(lat: Double, lon: Double): Int {
        if (lat in 56.0..64.0 && lon in 3.0..12.0) return 32
        if (lat >= 72.0 && lon >= 0.0 && lon < 42.0) {
            return when {
                lon < 9.0 -> 31
                lon < 21.0 -> 33
                lon < 33.0 -> 35
                else -> 37
            }
        }
        return (floor((lon + 180) / 6).toInt() + 1).coerceIn(1, 60)
    }

    private const val PLUS_DIGITS = "23456789CFGHJMPQRVWX"
    private const val PLUS_STEPS = 8000
}
