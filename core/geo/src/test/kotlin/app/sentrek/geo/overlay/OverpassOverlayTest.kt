package app.sentrek.geo.overlay

import app.sentrek.geo.BoundingBox
import app.sentrek.geo.Simplify
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.StringWriter

class OverpassOverlayTest {
    private fun fixture() = javaClass.getResourceAsStream("/overpass-overlay.json")!!.reader()

    @Test
    fun `converts trails and points into geojson features`() {
        val out = StringWriter()
        val count = OverpassOverlay.convert(fixture(), out)
        val json = out.toString()

        assertEquals(5, count)
        assertTrue(json.startsWith("""{"type":"FeatureCollection","features":[{"type":"Feature""""))
        assertTrue(json.contains(""""properties":{"s":1,"v":2,"id":"way/8233525"}"""))
        assertTrue(json.contains("[8.00614,45.62199]"))
        // All tags are kept (sorted), minus empty values and mapper bookkeeping.
        assertTrue(json.contains(""""properties":{"k":"cave","n":"Grotta \"del\" Lupo","id":"node/1","ele":"1520","natural":"cave_entrance"}"""))
        assertTrue(json.contains(""""properties":{"k":"cross","man_made":"cross","natural":"peak"},"geometry":{"type":"Point","coordinates":[7.65859,45.97633]}"""))
        assertTrue(json.endsWith("]}"))
    }

    @Test
    fun `skips unknown kinds and malformed geometry`() {
        val input = """{"elements":[
            {"type":"trail","id":1,"geometry":{"type":"LineString","coordinates":[[7.0,46.0],[7.1,46.1]]},"tags":{"sac":"bogus","vis":""}},
            {"type":"trail","id":2,"geometry":{"type":"Point","coordinates":[7.0,46.0]},"tags":{"sac":"hiking","vis":""}},
            {"type":"poi","id":3,"geometry":{"type":"Point","coordinates":[7.0,46.0]},"tags":{"natural":"tree","name":""}},
            {"type":"trail","id":4,"geometry":{"type":"LineString","coordinates":[[7.0,46.0],[7.1,46.1]]},"tags":{"sac":"alpine_hiking","vis":""}}
        ]}"""
        val out = StringWriter()
        assertEquals(1, OverpassOverlay.convert(input.reader(), out))
        assertTrue(out.toString().contains(""""s":4,"v":0"""))
    }

    @Test
    fun `query targets the cell bbox and asks for points`() {
        val q = OverpassOverlay.query(BoundingBox(45.5, 7.5, 46.0, 8.0))
        assertTrue(q.contains("[bbox:45.5,7.5,46.0,8.0]"))
        assertTrue(q.contains("cave_entrance"))
        assertTrue(q.contains("::=::,::id=id(),osm_type=type(),::geom=center(geom())"))
    }

    @Test
    fun `classifies hiking points`() {
        assertEquals(PoiKind.Cross, PoiKind.classify(mapOf("man_made" to "cross")))
        assertEquals(PoiKind.Cross, PoiKind.classify(mapOf("historic" to "wayside_cross")))
        assertEquals(PoiKind.Spring, PoiKind.classify(mapOf("natural" to "spring", "name" to "x")))
        assertNull(PoiKind.classify(mapOf("natural" to "tree")))
        assertEquals(PoiKind.Shelter, PoiKind.classify(mapOf("amenity" to "shelter", "shelter_type" to "basic_hut")))
        assertNull(PoiKind.classify(mapOf("amenity" to "shelter", "shelter_type" to "public_transport")))
        assertEquals(PoiKind.Guidepost, PoiKind.classify(mapOf("tourism" to "information", "information" to "guidepost")))
        assertEquals(PoiKind.DrinkingWater, PoiKind.classify(mapOf("amenity" to "water_point")))
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
