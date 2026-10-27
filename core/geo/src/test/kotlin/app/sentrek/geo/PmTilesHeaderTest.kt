package app.sentrek.geo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

class PmTilesHeaderTest {
    private fun header(tileType: Int, minZoom: Int = 0, maxZoom: Int = 15): ByteArray {
        val buf = ByteBuffer.allocate(PmTilesHeader.SIZE).order(ByteOrder.LITTLE_ENDIAN)
        buf.put("PMTiles".toByteArray()).put(3.toByte())
        buf.put(99, tileType.toByte()).put(100, minZoom.toByte()).put(101, maxZoom.toByte())
        buf.putInt(102, 75_000_000).putInt(106, 455_000_000).putInt(110, 80_000_000).putInt(114, 460_000_000)
        return buf.array()
    }

    @Test
    fun `reads raster header with bounds and zooms`() {
        val info = PmTilesHeader.read(header(tileType = 2, minZoom = 5, maxZoom = 16).inputStream())
        assertTrue(info.raster)
        assertEquals(5, info.minZoom)
        assertEquals(16, info.maxZoom)
        assertEquals(BoundingBox(45.5, 7.5, 46.0, 8.0), info.bounds)
    }

    @Test
    fun `detects vector tiles`() {
        assertFalse(PmTilesHeader.read(header(tileType = 1).inputStream()).raster)
    }

    @Test(expected = IOException::class)
    fun `rejects other files`() {
        PmTilesHeader.read(ByteArray(200).inputStream())
    }
}
