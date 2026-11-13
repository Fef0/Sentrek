package app.sentrek.geo

import java.io.ByteArrayInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.GZIPInputStream

/**
 * Enough of PMTiles v3 to find one tile of a file read in pieces (HTTP ranges): the header,
 * the directories and the tile ids. https://github.com/protomaps/PMTiles/blob/main/spec/v3/spec.md
 */
object PmTiles {
    /** The root directory always fits in the first 16 KB, with the header. */
    const val HEAD_BYTES = 16_384

    class Header(
        val rootOffset: Long,
        val rootLength: Long,
        val leafOffset: Long,
        val dataOffset: Long,
        val internalCompression: Int,
        val tileCompression: Int,
        val maxZoom: Int,
    )

    /** A run of [runLength] tiles from [tileId] stored at [offset] (from the data section); a leaf directory when [runLength] is 0. */
    class Entry(val tileId: Long, val offset: Long, val length: Int, val runLength: Long)

    fun header(bytes: ByteArray): Header {
        if (bytes.size < PmTilesHeader.SIZE || String(bytes, 0, 7, Charsets.US_ASCII) != "PMTiles" || bytes[7].toInt() != 3) {
            throw IOException("Not a PMTiles v3 file")
        }
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return Header(
            rootOffset = b.getLong(8),
            rootLength = b.getLong(16),
            leafOffset = b.getLong(40),
            dataOffset = b.getLong(56),
            internalCompression = b.get(97).toInt(),
            tileCompression = b.get(98).toInt(),
            maxZoom = b.get(101).toInt() and 0xFF,
        )
    }

    /** The entries of a directory, as stored ([compression] as in the header). */
    fun directory(stored: ByteArray, compression: Int): List<Entry> {
        val bytes = decompress(stored, compression)
        var i = 0
        fun varint(): Long {
            var result = 0L
            var shift = 0
            while (true) {
                val c = bytes[i++].toInt() and 0xFF
                result = result or ((c and 0x7F).toLong() shl shift)
                if (c < 0x80) return result
                shift += 7
            }
        }
        val n = varint().toInt()
        val ids = LongArray(n)
        var last = 0L
        for (k in 0 until n) { last += varint(); ids[k] = last }
        val runs = LongArray(n) { varint() }
        val lengths = IntArray(n) { varint().toInt() }
        val offsets = LongArray(n)
        for (k in 0 until n) {
            val v = varint()
            offsets[k] = if (v == 0L && k > 0) offsets[k - 1] + lengths[k - 1] else v - 1
        }
        return List(n) { Entry(ids[it], offsets[it], lengths[it], runs[it]) }
    }

    /** The entry holding [tileId]: its tile, or the leaf directory to look in next. */
    fun find(entries: List<Entry>, tileId: Long): Entry? {
        var lo = 0
        var hi = entries.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val c = entries[mid].tileId
            when {
                c < tileId -> lo = mid + 1
                c > tileId -> hi = mid - 1
                else -> return entries[mid]
            }
        }
        // The run before may cover it; a leaf directory covers everything up to the next entry.
        val before = entries.getOrNull(hi) ?: return null
        return when {
            before.runLength == 0L -> before
            tileId - before.tileId < before.runLength -> before
            else -> null
        }
    }

    /** The tile's place on the Hilbert curve, counting every tile of the zooms before. */
    fun tileId(z: Int, x: Int, y: Int): Long {
        var acc = 0L
        for (i in 0 until z) acc += 1L shl (2 * i)
        var tx = x.toLong()
        var ty = y.toLong()
        var d = 0L
        var s = (1L shl z) / 2
        while (s > 0) {
            val rx = if ((tx and s) > 0) 1L else 0L
            val ry = if ((ty and s) > 0) 1L else 0L
            d += s * s * ((3 * rx) xor ry)
            if (ry == 0L) {
                if (rx == 1L) {
                    tx = s - 1 - tx
                    ty = s - 1 - ty
                }
                val t = tx
                tx = ty
                ty = t
            }
            s /= 2
        }
        return acc + d
    }

    /** 1: none, 2: gzip. */
    fun decompress(bytes: ByteArray, compression: Int): ByteArray = when (compression) {
        0, 1 -> bytes
        2 -> GZIPInputStream(ByteArrayInputStream(bytes)).use { it.readBytes() }
        else -> throw IOException("Unsupported PMTiles compression $compression")
    }
}
