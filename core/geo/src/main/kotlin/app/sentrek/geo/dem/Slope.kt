package app.sentrek.geo.dem

import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * Steepness of the terrain, for shading steep slopes as avalanche maps do: 30–35°, 35–40°,
 * 40–45° and 45° or more. From terrain tiles of about 30 m, so a short steep step can read
 * gentler than it is: a help for planning, not an avalanche assessment.
 */
object Slope {
    /** Lower bounds of the shaded bands, in degrees. */
    val BANDS = floatArrayOf(30f, 35f, 40f, 45f)

    private const val EARTH_CIRCUMFERENCE_M = 40_075_016.686

    /** Metres per terrain pixel at [lat] for tiles of [tileSize] at [zoom] (Web Mercator). */
    fun metersPerPixel(lat: Double, zoom: Int, tileSize: Int = Terrarium.TILE_SIZE): Double =
        EARTH_CIRCUMFERENCE_M * cos(Math.toRadians(lat)) / (tileSize.toDouble() * (1 shl zoom))

    /**
     * Slope in degrees of each inner point of [heights] ([width] × [height], row by row, one
     * pixel of border all round), by Horn's method over its 3 × 3 neighbours. [pixelM] gives
     * the pixel size in metres for each row of the result. Returns (width − 2) × (height − 2).
     */
    fun degrees(heights: FloatArray, width: Int, height: Int, pixelM: (row: Int) -> Double): FloatArray {
        val w = width - 2
        val h = height - 2
        val out = FloatArray(w * h)
        for (j in 0 until h) {
            val d = 8 * pixelM(j)
            val top = j * width
            val mid = top + width
            val bottom = mid + width
            for (i in 0 until w) {
                val a = heights[top + i]
                val b = heights[top + i + 1]
                val c = heights[top + i + 2]
                val l = heights[mid + i]
                val r = heights[mid + i + 2]
                val g = heights[bottom + i]
                val k = heights[bottom + i + 1]
                val m = heights[bottom + i + 2]
                val dx = ((c + 2 * r + m) - (a + 2 * l + g)) / d
                val dy = ((g + 2 * k + m) - (a + 2 * b + c)) / d
                out[j * w + i] = (atan(sqrt(dx * dx + dy * dy)) * 180 / PI).toFloat()
            }
        }
        return out
    }

    /** 0 below 30°, else 1 to 4 for the band the slope falls in. */
    fun band(degrees: Float): Int {
        var band = 0
        for (lower in BANDS) if (degrees >= lower) band++
        return band
    }
}
