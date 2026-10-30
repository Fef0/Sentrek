package app.sentrek.geo.route

import app.sentrek.geo.Geo
import app.sentrek.geo.LatLon
import app.sentrek.geo.Sun
import app.sentrek.geo.dem.Panorama
import app.sentrek.geo.dem.TerrainTiles
import app.sentrek.geo.dem.Terrarium
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class ShadeTest {
    /** 21 June 2026 at [hour]:00 UTC. */
    private fun june(hour: Int) = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
        clear()
        set(2026, Calendar.JUNE, 21, hour, 0)
    }.timeInMillis

    @Test
    fun `the sun at noon and at night`() {
        // Zermatt, 11 UTC: half an hour before the sun is highest, just east of south.
        val noon = Sun.position(june(11), 46.0, 7.75)
        assertEquals(66.0, noon.altitude, 1.5)
        assertEquals(162.0, noon.azimuth, 4.0)
        assertTrue(Sun.position(june(23), 46.0, 7.75).altitude < 0)
        // Morning: in the east.
        assertTrue(Sun.position(june(5), 46.0, 7.75).azimuth in 50.0..110.0)
    }

    /** Flat at 1000 m, with a 3000 m wall a tile wide two tiles east of [from]. */
    private val from = LatLon(46.0, 7.0)
    private val wallX = Terrarium.locate(46.0, 7.0).key.x + 2
    private val tiles = TerrainTiles { key ->
        val h = if (key.x == wallX) 3000f else 1000f
        Terrarium.Tile(FloatArray(Terrarium.TILE_SIZE * Terrarium.TILE_SIZE) { h })
    }

    @Test
    fun `the wall shades the morning, not the afternoon`() {
        val p = Panorama(tiles)
        // Walking west at 4 km/h, from 5 UTC (early morning, sun low in the east behind the wall).
        val points = (0..40).map { i ->
            Shade.Point(i * 250.0, june(5) + i * 225_000L, Geo.destination(from, 270.0, i * 250.0))
        }
        val stretches = Shade.stretches(points, p)
        assertFalse(stretches.first().sun)
        // Out in the open afternoon.
        val afternoon = Shade.stretches(listOf(Shade.Point(0.0, june(13), from), Shade.Point(500.0, june(14), from)), p)
        assertTrue(afternoon.single().sun)
    }

    @Test
    fun `trees shade the afternoon too`() {
        val p = Panorama(tiles)
        // West of the start, out of the wall's way; the middle stretch in a wood.
        val points = (0..10).map { i -> Shade.Point(i * 500.0, june(13) + i * 450_000L, Geo.destination(from, 270.0, i * 500.0)) }
        val stretches = Shade.stretches(points, p) { pos -> Geo.distance(from, pos) in 1_800.0..3_200.0 }
        assertEquals(listOf(true, false, true), stretches.map { it.sun })
    }

    @Test
    fun `long sunny stretches only`() {
        val now = 0L
        val short = Shade.Stretch(true, 0.0, 500.0, 0, 10 * 60_000L)
        val shade = Shade.Stretch(false, 500.0, 2000.0, 10 * 60_000L, 60 * 60_000L)
        val long = Shade.Stretch(true, 2000.0, 8000.0, 60 * 60_000L, 180 * 60_000L)
        assertEquals(long, Shade.nextSunny(listOf(short, shade, long), now))
        assertNull(Shade.nextSunny(listOf(short, shade), now))
        assertNotNull(Shade.nextSunny(listOf(long), now))
    }
}
