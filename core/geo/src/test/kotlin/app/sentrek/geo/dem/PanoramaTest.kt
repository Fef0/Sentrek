package app.sentrek.geo.dem

import app.sentrek.geo.Geo
import app.sentrek.geo.LatLon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PanoramaTest {
    private val from = LatLon(46.0, 7.0)
    private val wallX = Terrarium.locate(46.0, 7.0).key.x + 2

    /** Flat at 1000 m, with a 3000 m wall a whole tile wide two tiles east. */
    private val tiles = TerrainTiles { key ->
        val h = if (key.x == wallX) 3000f else 1000f
        Terrarium.Tile(FloatArray(Terrarium.TILE_SIZE * Terrarium.TILE_SIZE) { h })
    }

    @Test
    fun `a peak in the open is seen, one behind the wall isn't`() {
        val p = Panorama(tiles)
        val north = Geo.destination(from, 0.0, 10_000.0).copy(ele = 2000.0)
        val behind = Geo.destination(from, 90.0, 29_000.0).copy(ele = 2500.0)
        val seen = p.peaks(from, 2.0, listOf("North" to north, "Behind" to behind))
        assertEquals(listOf("North"), seen.map { it.name })
        assertEquals(0.0, seen[0].azimuth, 0.5)
        assertTrue(seen[0].elevation > 5)
    }

    @Test
    fun `the skyline rises where the wall is`() {
        val sky = Panorama(tiles).skyline(from, 2.0)
        assertTrue(sky.at(90.0) > 3f)
        assertTrue(sky.at(270.0) < 0.5f)
    }

    @Test
    fun `destination and distance agree`() {
        val p = Geo.destination(from, 45.0, 12_345.0)
        assertEquals(12_345.0, Geo.distance(from, p), 1.0)
        assertEquals(45.0, Geo.bearing(from, p), 0.1)
    }
}
