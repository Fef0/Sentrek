package app.sentrek.geo.dem

import app.sentrek.geo.TileMath
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.tan

/**
 * Elevation from Terrarium PNG tiles (the AWS terrain tiles the map's hillshade uses):
 * height = R × 256 + G + B / 256 − 32768 metres.
 */
object Terrarium {
    /** z12: about 25 m per pixel in the Alps, as detailed as the source data gets. */
    const val ZOOM = 12
    const val TILE_SIZE = 256

    fun decode(argb: Int): Float {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        return r * 256f + g + b / 256f - 32768f
    }

    /** A decoded tile: [TILE_SIZE]² heights, row by row. */
    class Tile(val heights: FloatArray) {
        init {
            require(heights.size == TILE_SIZE * TILE_SIZE)
        }

        operator fun get(x: Int, y: Int): Float = heights[y.coerceIn(0, TILE_SIZE - 1) * TILE_SIZE + x.coerceIn(0, TILE_SIZE - 1)]
    }

    data class TileKey(val x: Int, val y: Int, val zoom: Int = ZOOM)

    /** The tile holding a position, and the position inside it in pixels. */
    data class Sample(val key: TileKey, val px: Double, val py: Double)

    fun locate(lat: Double, lon: Double, zoom: Int = ZOOM): Sample {
        val n = (1 shl zoom).toDouble()
        val x = (lon + 180.0) / 360.0 * n
        val latRad = Math.toRadians(lat.coerceIn(-TileMath.MAX_MERCATOR_LAT, TileMath.MAX_MERCATOR_LAT))
        val y = (1.0 - ln(tan(latRad) + 1.0 / cos(latRad)) / PI) / 2.0 * n
        val tx = floor(x).toInt().coerceIn(0, n.toInt() - 1)
        val ty = floor(y).toInt().coerceIn(0, n.toInt() - 1)
        // Pixel centres are at +0.5: shift so bilinear weights line up with them.
        return Sample(TileKey(tx, ty, zoom), (x - tx) * TILE_SIZE - 0.5, (y - ty) * TILE_SIZE - 0.5)
    }

    /** Bilinear height at a [Sample] (edge pixels are repeated at the tile border). */
    fun height(tile: Tile, sample: Sample): Float {
        val x0 = floor(sample.px).toInt()
        val y0 = floor(sample.py).toInt()
        val fx = (sample.px - x0).toFloat()
        val fy = (sample.py - y0).toFloat()
        val top = tile[x0, y0] * (1 - fx) + tile[x0 + 1, y0] * fx
        val bottom = tile[x0, y0 + 1] * (1 - fx) + tile[x0 + 1, y0 + 1] * fx
        return top * (1 - fy) + bottom * fy
    }
}

/** Decoded terrain tiles, from wherever the app keeps them. Blocking: call off the main thread. */
fun interface TerrainTiles {
    /** Null where the tile can't be had (offline, outside the downloaded areas). */
    fun tile(key: Terrarium.TileKey): Terrarium.Tile?
}
