package app.sentrek.geo.overlay

import app.sentrek.geo.BoundingBox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayTrailsTest {
    @Test
    fun `reads the paths in the box, not routes or points`() {
        val input = """{"type":"FeatureCollection","features":[
            {"type":"Feature","properties":{"s":3,"v":0,"id":"way/1"},"geometry":{"type":"LineString","coordinates":[[12.0,46.0],[12.01,46.01]]}},
            {"type":"Feature","properties":{"v":0,"f":2,"id":"way/2"},"geometry":{"type":"LineString","coordinates":[[12.01,46.01],[12.02,46.02]]}},
            {"type":"Feature","properties":{"v":0,"id":"way/3"},"geometry":{"type":"LineString","coordinates":[[13.0,47.0],[13.01,47.01]]}},
            {"type":"Feature","properties":{"route_1_network":"lwn","w":1,"rid":7},"geometry":{"type":"LineString","coordinates":[[12.0,46.0],[12.1,46.1]]}},
            {"type":"Feature","properties":{"k":"alpine_hut","n":"Rifugio"},"geometry":{"type":"Point","coordinates":[12.0,46.0]}}
        ]}"""
        val lines = OverlayTrails.read(input.reader(), BoundingBox(45.9, 11.9, 46.1, 12.1))
        assertEquals(2, lines.size)
        assertEquals(3, lines[0].grade)
        assertTrue(lines[1].ferrata)
        assertEquals(OverlayTrails.FERRATA_COST, lines[1].cost, 0.0)
    }
}
