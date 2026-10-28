package app.sentrek.geo

import java.util.Locale
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

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
        val lon0 = Math.toRadians((zone - 1) * 6 - 180 + 3.0)
        val phi = Math.toRadians(p.lat)
        val n = A / sqrt(1 - E2 * sin(phi) * sin(phi))
        val t = tan(phi) * tan(phi)
        val c = EP2 * cos(phi) * cos(phi)
        val a = cos(phi) * (Math.toRadians(p.lon) - lon0)
        val easting = K0 * n * (a + (1 - t + c) * a * a * a / 6 + (5 - 18 * t + t * t + 72 * c - 58 * EP2) * Math.pow(a, 5.0) / 120) + 500_000
        var northing = K0 * (meridianArc(phi) + n * tan(phi) * (a * a / 2 + (5 - t + 9 * c + 4 * c * c) * Math.pow(a, 4.0) / 24 +
            (61 - 58 * t + t * t + 600 * c - 330 * EP2) * Math.pow(a, 6.0) / 720))
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

    /** Distance along the meridian from the equator to [phi] (radians), WGS84. */
    private fun meridianArc(phi: Double): Double = A * (
        (1 - E2 / 4 - 3 * E2 * E2 / 64 - 5 * E2 * E2 * E2 / 256) * phi -
            (3 * E2 / 8 + 3 * E2 * E2 / 32 + 45 * E2 * E2 * E2 / 1024) * sin(2 * phi) +
            (15 * E2 * E2 / 256 + 45 * E2 * E2 * E2 / 1024) * sin(4 * phi) -
            (35 * E2 * E2 * E2 / 3072) * sin(6 * phi)
        )

    private const val A = 6_378_137.0
    private const val F = 1 / 298.257223563
    private const val E2 = F * (2 - F)
    private const val EP2 = E2 / (1 - E2)
    private const val K0 = 0.9996

    private const val PLUS_DIGITS = "23456789CFGHJMPQRVWX"
    private const val PLUS_STEPS = 8000
}
