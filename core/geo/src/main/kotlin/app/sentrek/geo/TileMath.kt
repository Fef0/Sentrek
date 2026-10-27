package app.sentrek.geo

import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.tan

/** Web Mercator (slippy map) tile arithmetic. */
object TileMath {
    const val MAX_MERCATOR_LAT = 85.05112878

    fun tileX(lon: Double, zoom: Int): Int {
        val n = 1 shl zoom
        return floor((lon + 180.0) / 360.0 * n).toInt().coerceIn(0, n - 1)
    }

    fun tileY(lat: Double, zoom: Int): Int {
        val n = 1 shl zoom
        val latRad = Math.toRadians(lat.coerceIn(-MAX_MERCATOR_LAT, MAX_MERCATOR_LAT))
        val y = (1.0 - ln(tan(latRad) + 1.0 / kotlin.math.cos(latRad)) / PI) / 2.0 * n
        return floor(y).toInt().coerceIn(0, n - 1)
    }

    /** Number of tiles covering [box] for every zoom in [minZoom]..[maxZoom]. */
    fun tileCount(box: BoundingBox, minZoom: Int, maxZoom: Int): Long =
        (minZoom..maxZoom).sumOf { z ->
            val xs = tileX(box.east, z) - tileX(box.west, z) + 1
            val ys = tileY(box.south, z) - tileY(box.north, z) + 1
            xs.toLong() * ys.toLong()
        }
}
