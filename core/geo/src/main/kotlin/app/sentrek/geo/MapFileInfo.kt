package app.sentrek.geo

import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** What an imported map file contains. */
data class MapFileInfo(
    val raster: Boolean,
    val minZoom: Int,
    val maxZoom: Int,
    val bounds: BoundingBox?,
)

/** PMTiles v3 header: https://github.com/protomaps/PMTiles/blob/main/spec/v3/spec.md */
object PmTilesHeader {
    const val SIZE = 127
    private val MAGIC = "PMTiles".toByteArray()

    private const val TILE_TYPE_MVT = 1

    fun read(input: InputStream): MapFileInfo {
        // Not InputStream.readNBytes: it needs Android 13, and this runs on old phones too.
        val bytes = ByteArray(SIZE)
        var read = 0
        while (read < SIZE) {
            val n = input.read(bytes, read, SIZE - read)
            if (n < 0) break
            read += n
        }
        if (read < SIZE || !bytes.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) {
            throw IOException("Not a PMTiles file")
        }
        if (bytes[7].toInt() != 3) throw IOException("Unsupported PMTiles version ${bytes[7]}")

        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val tileType = buf.get(99).toInt()
        val bounds = runCatching {
            BoundingBox(
                south = buf.getInt(106) / 1e7,
                west = buf.getInt(102) / 1e7,
                north = buf.getInt(114) / 1e7,
                east = buf.getInt(110) / 1e7,
            )
        }.getOrNull()
        return MapFileInfo(
            raster = tileType != TILE_TYPE_MVT,
            minZoom = buf.get(100).toInt() and 0xFF,
            maxZoom = buf.get(101).toInt() and 0xFF,
            bounds = bounds,
        )
    }
}
