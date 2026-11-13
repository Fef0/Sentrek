package app.sentrek.geo.overlay

import app.sentrek.geo.BoundingBox
import org.junit.Assert.assertEquals
import org.junit.Test

class NamedLinesTest {
    @Test
    fun `reads via ferratas and routes, each in one piece`() {
        val input = """{"type":"FeatureCollection","features":[
            {"type":"Feature","properties":{"v":0,"f":3,"n":"Ferrata  Merlone","id":"way/1"},"geometry":{"type":"LineString","coordinates":[[12.0,46.0],[12.01,46.01]]}},
            {"type":"Feature","properties":{"v":0,"f":3,"n":"Ferrata Merlone","id":"way/2"},"geometry":{"type":"LineString","coordinates":[[12.01,46.01],[12.02,46.02]]}},
            {"type":"Feature","properties":{"route_1_network":"lwn","w":1,"rid":7,"n":"Alta Via 1","ref":"AV1"},"geometry":{"type":"LineString","coordinates":[[12.0,46.0],[12.1,46.1]]}},
            {"type":"Feature","properties":{"route_1_network":"lwn","w":1,"rid":7,"n":"Alta Via 1","ref":"AV1"},"geometry":{"type":"LineString","coordinates":[[12.3,46.3],[12.4,46.4]]}},
            {"type":"Feature","properties":{"route_1_network":"lwn","w":1,"rid":8,"ref":"708"},"geometry":{"type":"LineString","coordinates":[[12.0,46.0],[12.1,46.1]]}},
            {"type":"Feature","properties":{"v":0,"id":"way/3"},"geometry":{"type":"LineString","coordinates":[[12.0,46.0],[12.1,46.1]]}},
            {"type":"Feature","properties":{"k":"alpine_hut","n":"Rifugio"},"geometry":{"type":"Point","coordinates":[12.0,46.0]}}
        ]}"""
        val lines = NamedLines.read(input.reader())
        assertEquals(3, lines.size)
        val ferrata = lines.single { it.kind == NamedLine.Kind.Ferrata }
        assertEquals("Ferrata Merlone", ferrata.name)
        assertEquals(3, ferrata.grade)
        assertEquals(BoundingBox(46.0, 12.0, 46.02, 12.02), ferrata.box)
        val route = lines.single { it.routeId == 7L }
        assertEquals(BoundingBox(46.0, 12.0, 46.4, 12.4), route.box)
        assertEquals("Alta Via 1 AV1", route.searchText)
        // A route with only a number is found by it.
        assertEquals("708", lines.single { it.routeId == 8L }.name)
    }

    @Test
    fun `pieces of a path far apart stay apart`() {
        val near = NamedLine("Sentiero del Monte", NamedLine.Kind.Path, BoundingBox(46.0, 12.0, 46.01, 12.01))
        val touching = near.copy(box = BoundingBox(46.01, 12.01, 46.02, 12.02))
        val far = near.copy(box = BoundingBox(46.5, 12.5, 46.51, 12.51))
        val merged = NamedLines.merge(listOf(near, touching, far))
        assertEquals(2, merged.size)
        assertEquals(BoundingBox(46.0, 12.0, 46.02, 12.02), merged[0].box)
    }

    @Test
    fun `a route named like its via ferrata is the via ferrata`() {
        val box = BoundingBox(46.0, 12.0, 46.01, 12.01)
        val ferrata = NamedLine("Via ferrata Merlone", NamedLine.Kind.Ferrata, box, grade = 2)
        val route = NamedLine("Via ferrata Merlone", NamedLine.Kind.Route, box, routeId = 1)
        val other = NamedLine("Via ferrata Merlone", NamedLine.Kind.Route, BoundingBox(47.0, 13.0, 47.01, 13.01), routeId = 2)
        assertEquals(listOf(ferrata, other), NamedLines.withoutRepeats(listOf(ferrata, route, other)))
    }
}
