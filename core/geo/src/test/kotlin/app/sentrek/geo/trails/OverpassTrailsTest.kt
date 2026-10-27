package app.sentrek.geo.trails

import app.sentrek.geo.Simplify
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.StringWriter

class OverpassTrailsTest {
    private fun fixture() = javaClass.getResourceAsStream("/overpass-trails.json")!!.reader()

    @Test
    fun `converts overpass elements into geojson features`() {
        val out = StringWriter()
        val count = OverpassTrails.convert(fixture(), out)
        val json = out.toString()

        assertEquals(3, count)
        assertTrue(json.startsWith("""{"type":"FeatureCollection","features":[{"type":"Feature""""))
        assertTrue(json.contains(""""properties":{"s":1,"v":2}"""))
        assertTrue(json.contains("[8.00614,45.62199]"))
        assertTrue(json.endsWith("]}"))
    }

    @Test
    fun `skips elements without a known sac scale or geometry`() {
        val input = """{"elements":[
            {"type":"way","id":1,"geometry":{"type":"LineString","coordinates":[[7.0,46.0],[7.1,46.1]]},"tags":{"sac":"bogus","vis":""}},
            {"type":"way","id":2,"geometry":{"type":"Point","coordinates":[7.0,46.0]},"tags":{"sac":"hiking","vis":""}},
            {"type":"way","id":3,"geometry":{"type":"LineString","coordinates":[[7.0,46.0],[7.1,46.1]]},"tags":{"sac":"alpine_hiking","vis":""}}
        ]}"""
        val out = StringWriter()
        assertEquals(1, OverpassTrails.convert(input.reader(), out))
        assertTrue(out.toString().contains(""""s":4,"v":0"""))
    }

    @Test
    fun `query targets the cell bbox`() {
        val q = OverpassTrails.query(app.sentrek.geo.BoundingBox(45.5, 7.5, 46.0, 8.0))
        assertTrue(q.contains("[bbox:45.5,7.5,46.0,8.0]"))
    }
}

class SimplifyTest {
    @Test
    fun `drops collinear points but keeps corners`() {
        // Straight east for ~150 m, then a sharp turn north.
        val lons = doubleArrayOf(7.000, 7.001, 7.002, 7.002)
        val lats = doubleArrayOf(46.000, 46.000, 46.000, 46.001)
        assertArrayEquals(intArrayOf(0, 2, 3), Simplify.douglasPeucker(lons, lats, 2.0))
    }

    @Test
    fun `keeps deviations above tolerance`() {
        // Middle point is ~11 m off the straight line.
        val lons = doubleArrayOf(7.000, 7.001, 7.002)
        val lats = doubleArrayOf(46.000, 46.0001, 46.000)
        assertArrayEquals(intArrayOf(0, 1, 2), Simplify.douglasPeucker(lons, lats, 2.0))
        assertArrayEquals(intArrayOf(0, 2), Simplify.douglasPeucker(lons, lats, 20.0))
    }
}
