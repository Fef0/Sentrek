package app.sentrek.geo.overlay

/**
 * Just enough of a Mapbox Vector Tile reader for the polygons of one layer: the map's own
 * downloaded tiles say where the forests are. Protocol buffers read by hand: the format is
 * small, and a library for it would weigh more than this file.
 */
object VectorTile {
    /** One polygon feature: its tags (string values only) and rings, in tile units (0 to [extent]). */
    class Polygon(val tags: Map<String, String>, val rings: List<IntArray>, val extent: Int) {
        /** Whether the point at [x], [y] (tile units) is inside: even-odd, so holes stay out. */
        fun contains(x: Double, y: Double): Boolean {
            var inside = false
            for (ring in rings) {
                val n = ring.size / 2
                var j = n - 1
                for (i in 0 until n) {
                    val xi = ring[2 * i].toDouble()
                    val yi = ring[2 * i + 1].toDouble()
                    val xj = ring[2 * j].toDouble()
                    val yj = ring[2 * j + 1].toDouble()
                    if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) inside = !inside
                    j = i
                }
            }
            return inside
        }
    }

    /** The polygons of [layer] in the tile [bytes] (uncompressed) that [keep] accepts. */
    fun polygons(bytes: ByteArray, layer: String, keep: (Map<String, String>) -> Boolean): List<Polygon> {
        val tile = Reader(bytes, 0, bytes.size)
        while (tile.more()) {
            val (field, wire) = tile.key()
            if (field == LAYERS && wire == LENGTH) {
                val l = tile.message()
                readLayer(l, layer, keep)?.let { return it }
            } else {
                tile.skip(wire)
            }
        }
        return emptyList()
    }

    /** The layer's polygons, or null when it's another layer. */
    private fun readLayer(r: Reader, wanted: String, keep: (Map<String, String>) -> Boolean): List<Polygon>? {
        var name: String? = null
        val keys = mutableListOf<String>()
        val values = mutableListOf<String>()
        val features = mutableListOf<Reader>()
        var extent = 4096
        while (r.more()) {
            val (field, wire) = r.key()
            when {
                field == 1 && wire == LENGTH -> name = r.string()
                field == 2 && wire == LENGTH -> features += r.message()
                field == 3 && wire == LENGTH -> keys += r.string()
                field == 4 && wire == LENGTH -> values += readValue(r.message())
                field == 5 && wire == VARINT -> extent = r.varint().toInt()
                else -> r.skip(wire)
            }
            // The name comes first in practice: another layer is skipped at once.
            if (name != null && name != wanted) return null
        }
        if (name != wanted) return null
        return features.mapNotNull { readFeature(it, keys, values, extent, keep) }
    }

    private fun readValue(r: Reader): String {
        var value = ""
        while (r.more()) {
            val (field, wire) = r.key()
            if (field == 1 && wire == LENGTH) value = r.string() else r.skip(wire)
        }
        return value
    }

    private fun readFeature(
        r: Reader,
        keys: List<String>,
        values: List<String>,
        extent: Int,
        keep: (Map<String, String>) -> Boolean,
    ): Polygon? {
        var tags = emptyMap<String, String>()
        var type = 0
        var geometry: Reader? = null
        while (r.more()) {
            val (field, wire) = r.key()
            when {
                field == 2 && wire == LENGTH -> {
                    val t = r.message()
                    val map = HashMap<String, String>()
                    while (t.more()) {
                        val k = t.varint().toInt()
                        val v = t.varint().toInt()
                        if (k in keys.indices && v in values.indices) map[keys[k]] = values[v]
                    }
                    tags = map
                }
                field == 3 && wire == VARINT -> type = r.varint().toInt()
                field == 4 && wire == LENGTH -> geometry = r.message()
                else -> r.skip(wire)
            }
        }
        if (type != POLYGON || geometry == null || !keep(tags)) return null
        return Polygon(tags, rings(geometry), extent)
    }

    /** MoveTo, LineTo, ClosePath with zigzag deltas: the rings as x, y pairs. */
    private fun rings(g: Reader): List<IntArray> {
        val rings = mutableListOf<IntArray>()
        var ring = ArrayList<Int>()
        var x = 0
        var y = 0
        while (g.more()) {
            val command = g.varint().toInt()
            val id = command and 0x7
            val count = command ushr 3
            when (id) {
                MOVE_TO, LINE_TO -> repeat(count) {
                    if (id == MOVE_TO && ring.isNotEmpty()) {
                        rings += ring.toIntArray()
                        ring = ArrayList()
                    }
                    x += zigzag(g.varint())
                    y += zigzag(g.varint())
                    ring += x
                    ring += y
                }
                CLOSE_PATH -> {
                    if (ring.isNotEmpty()) rings += ring.toIntArray()
                    ring = ArrayList()
                }
                else -> return rings
            }
        }
        if (ring.isNotEmpty()) rings += ring.toIntArray()
        return rings
    }

    private fun zigzag(n: Long): Int = ((n ushr 1) xor -(n and 1)).toInt()

    /** A window on the bytes of one message. */
    private class Reader(private val bytes: ByteArray, private var pos: Int, private val end: Int) {
        fun more() = pos < end

        fun key(): Pair<Int, Int> {
            val k = varint().toInt()
            return (k ushr 3) to (k and 0x7)
        }

        fun varint(): Long {
            var result = 0L
            var shift = 0
            while (pos < end) {
                val b = bytes[pos++].toInt()
                result = result or ((b and 0x7F).toLong() shl shift)
                if (b and 0x80 == 0) return result
                shift += 7
            }
            return result
        }

        fun message(): Reader {
            val length = varint().toInt()
            val start = pos
            pos = minOf(end, pos + length)
            return Reader(bytes, start, pos)
        }

        fun string(): String {
            val m = message()
            return String(bytes, m.pos, m.end - m.pos, Charsets.UTF_8)
        }

        fun skip(wire: Int) {
            when (wire) {
                VARINT -> varint()
                FIXED64 -> pos += 8
                LENGTH -> message()
                FIXED32 -> pos += 4
                else -> pos = end
            }
        }
    }

    private const val LAYERS = 3
    private const val VARINT = 0
    private const val FIXED64 = 1
    private const val LENGTH = 2
    private const val FIXED32 = 5
    private const val POLYGON = 3
    private const val MOVE_TO = 1
    private const val LINE_TO = 2
    private const val CLOSE_PATH = 7
}
