package app.sentrek.geo.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class VectorTileTest {
    // A minimal protobuf writer, enough to build a tile.
    private class Out {
        val bytes = ByteArrayOutputStream()
        fun varint(v: Long) {
            var n = v
            while (n and 0x7F.inv().toLong() != 0L) {
                bytes.write(((n and 0x7F) or 0x80).toInt())
                n = n ushr 7
            }
            bytes.write(n.toInt())
        }
        fun key(field: Int, wire: Int) = varint(((field shl 3) or wire).toLong())
        fun bytes(field: Int, data: ByteArray) {
            key(field, 2)
            varint(data.size.toLong())
            bytes.write(data)
        }
        fun string(field: Int, s: String) = bytes(field, s.toByteArray())
        fun packed(field: Int, values: List<Long>) = bytes(field, Out().apply { values.forEach { varint(it) } }.bytes.toByteArray())
    }

    private fun zz(n: Int) = ((n shl 1) xor (n shr 31)).toLong()

    /** Rings as absolute points: MoveTo, LineTo the rest, ClosePath. */
    private fun geometry(vararg rings: List<Pair<Int, Int>>): List<Long> {
        val out = mutableListOf<Long>()
        var x = 0
        var y = 0
        for (ring in rings) {
            out += ((1 shl 3) or 1).toLong()
            out += zz(ring[0].first - x); out += zz(ring[0].second - y)
            x = ring[0].first; y = ring[0].second
            out += (((ring.size - 1) shl 3) or 2).toLong()
            for ((px, py) in ring.drop(1)) {
                out += zz(px - x); out += zz(py - y)
                x = px; y = py
            }
            out += 7L
        }
        return out
    }

    private fun tile(): ByteArray {
        val wood = Out().apply {
            packed(2, listOf(0, 0)) // class = wood
            key(3, 0); varint(3)
            // A forest square with a clearing in the middle.
            packed(4, geometry(
                listOf(100 to 100, 1000 to 100, 1000 to 1000, 100 to 1000),
                listOf(400 to 400, 400 to 600, 600 to 600, 600 to 400),
            ))
        }
        val grass = Out().apply {
            packed(2, listOf(0, 1)) // class = grass
            key(3, 0); varint(3)
            packed(4, geometry(listOf(2000 to 2000, 3000 to 2000, 3000 to 3000, 2000 to 3000)))
        }
        val landcover = Out().apply {
            key(15, 0); varint(2)
            string(1, "landcover")
            bytes(2, wood.bytes.toByteArray())
            bytes(2, grass.bytes.toByteArray())
            string(3, "class")
            bytes(4, Out().apply { string(1, "wood") }.bytes.toByteArray())
            bytes(4, Out().apply { string(1, "grass") }.bytes.toByteArray())
            key(5, 0); varint(4096)
        }
        val water = Out().apply {
            string(1, "water")
            key(5, 0); varint(4096)
        }
        return Out().apply {
            bytes(3, water.bytes.toByteArray())
            bytes(3, landcover.bytes.toByteArray())
        }.bytes.toByteArray()
    }

    @Test
    fun `finds the forest and leaves the clearing out`() {
        val woods = VectorTile.polygons(tile(), "landcover") { it["class"] == "wood" }
        assertEquals(1, woods.size)
        val wood = woods.single()
        assertEquals(4096, wood.extent)
        assertTrue(wood.contains(200.0, 200.0))
        assertFalse(wood.contains(500.0, 500.0))
        assertFalse(wood.contains(2500.0, 2500.0))
    }

    @Test
    fun `another layer or nothing`() {
        assertEquals(2, VectorTile.polygons(tile(), "landcover") { true }.size)
        assertTrue(VectorTile.polygons(tile(), "transportation") { true }.isEmpty())
        assertTrue(VectorTile.polygons(ByteArray(0), "landcover") { true }.isEmpty())
    }

    @Test
    fun `reads named points with their heights`() {
        val etna = Out().apply {
            packed(2, listOf(0, 0, 1, 1)) // name = Etna, ele = 3357
            key(3, 0); varint(1)
            packed(4, listOf(((1 shl 3) or 1).toLong(), zz(1200), zz(800)))
        }
        val nameless = Out().apply {
            packed(2, listOf(1, 1))
            key(3, 0); varint(1)
            packed(4, listOf(((1 shl 3) or 1).toLong(), zz(10), zz(10)))
        }
        val peaks = Out().apply {
            key(15, 0); varint(2)
            string(1, "mountain_peak")
            bytes(2, etna.bytes.toByteArray())
            bytes(2, nameless.bytes.toByteArray())
            string(3, "name")
            string(3, "ele")
            bytes(4, Out().apply { string(1, "Etna") }.bytes.toByteArray())
            bytes(4, Out().apply { key(4, 0); varint(3357) }.bytes.toByteArray())
            key(5, 0); varint(4096)
        }
        val bytes = Out().apply { bytes(3, peaks.bytes.toByteArray()) }.bytes.toByteArray()
        val points = VectorTile.points(bytes, "mountain_peak") { "name" in it }
        assertEquals(1, points.size)
        assertEquals("Etna", points[0].tags["name"])
        assertEquals("3357", points[0].tags["ele"])
        assertEquals(1200 to 800, points[0].x to points[0].y)
        // Polygons of that layer: none, the points aren't taken for them.
        assertTrue(VectorTile.polygons(bytes, "mountain_peak") { true }.isEmpty())
    }
}
