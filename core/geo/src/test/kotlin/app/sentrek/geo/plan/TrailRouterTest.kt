package app.sentrek.geo.plan

import app.sentrek.geo.Geo
import app.sentrek.geo.LatLon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos

class TrailRouterTest {
    /** A point [east] and [north] metres from 46°N 7°E. */
    private fun m(east: Double, north: Double): LatLon {
        val ky = Geo.EARTH_RADIUS_M * Math.toRadians(1.0)
        return LatLon(46.0 + north / ky, 7.0 + east / (ky * cos(Math.toRadians(46.0))))
    }

    private fun <T> must(value: T?): T {
        assertNotNull(value)
        return value!!
    }

    private fun near(expected: LatLon, actual: LatLon, toleranceM: Double = 1.0) =
        assertTrue("$actual is ${Geo.distance(expected, actual)} m from $expected", Geo.distance(expected, actual) <= toleranceM)

    @Test
    fun `follows the lines through a shared vertex`() {
        // An L: east 500 m, then north 500 m.
        val graph = TrailGraph(listOf(TrailLine(listOf(m(0.0, 0.0), m(500.0, 0.0))), TrailLine(listOf(m(500.0, 0.0), m(500.0, 500.0)))))
        val route = must(graph.route(m(0.0, 10.0), m(510.0, 500.0), maxSnapM = 30.0))
        near(m(0.0, 0.0), route.first())
        near(m(500.0, 0.0), route[1])
        near(m(500.0, 500.0), route.last())
        assertEquals(1000.0, Geo.length(route), 2.0)
    }

    @Test
    fun `joins a line ending on another one without a shared vertex`() {
        // A long east-west trail, and a side trail ending on it 1 m off, between its vertices.
        val main = TrailLine(listOf(m(0.0, 0.0), m(1000.0, 0.0)))
        val side = TrailLine(listOf(m(400.0, 1.0), m(400.0, 400.0)))
        val route = must(TrailGraph(listOf(main, side)).route(m(0.0, 0.0), m(400.0, 400.0), 30.0))
        assertEquals(800.0, Geo.length(route), 3.0)
    }

    @Test
    fun `prefers a trail to a cheaper-looking road`() {
        // Road straight across (1000 m), trail around it (1200 m): roads cost 1.5 per metre.
        val road = TrailLine(listOf(m(0.0, 0.0), m(1000.0, 0.0)), cost = 1.5)
        val trail = TrailLine(listOf(m(0.0, 0.0), m(100.0, 300.0), m(900.0, 300.0), m(1000.0, 0.0)))
        val route = must(TrailGraph(listOf(road, trail)).route(m(0.0, 0.0), m(1000.0, 0.0), 30.0))
        assertTrue(route.any { Geo.distance(it, m(500.0, 300.0)) < 450 })
        assertTrue(Geo.length(route) > 1100)
    }

    @Test
    fun `starts and ends where the taps meet the lines, mid-segment`() {
        val graph = TrailGraph(listOf(TrailLine(listOf(m(0.0, 0.0), m(1000.0, 0.0))), TrailLine(listOf(m(1000.0, 0.0), m(1000.0, 1000.0)))))
        val route = must(graph.route(m(300.0, 20.0), m(980.0, 600.0), 30.0))
        near(m(300.0, 0.0), route.first())
        near(m(1000.0, 600.0), route.last())
        assertEquals(1300.0, Geo.length(route), 2.0)
        // Both on the same segment: just the piece between them.
        val short = must(graph.route(m(100.0, 5.0), m(200.0, -5.0), 30.0))
        assertEquals(100.0, Geo.length(short), 1.0)
    }

    @Test
    fun `no route when off the network or not connected`() {
        val a = TrailLine(listOf(m(0.0, 0.0), m(500.0, 0.0)))
        val b = TrailLine(listOf(m(0.0, 100.0), m(500.0, 100.0)))
        val graph = TrailGraph(listOf(a, b))
        assertNull(graph.route(m(0.0, 0.0), m(500.0, 100.0), 30.0))
        assertNull(graph.route(m(0.0, 50.0), m(500.0, 0.0), 30.0))
        near(m(250.0, 0.0), must(graph.snap(m(250.0, 20.0), 30.0)))
        assertNull(graph.snap(m(250.0, 50.0), 30.0))
        assertNull(TrailGraph(emptyList()).route(m(0.0, 0.0), m(1.0, 1.0), 30.0))
    }

    @Test
    fun `lines split across tiles meet again`() {
        // The same trail cut at a tile edge: two pieces sharing the edge vertex.
        val west = TrailLine(listOf(m(0.0, 0.0), m(250.0, 50.0), m(500.0, 0.0)))
        val east = TrailLine(listOf(m(500.0, 0.0), m(750.0, -50.0), m(1000.0, 0.0)))
        val route = must(TrailGraph(listOf(east, west)).route(m(0.0, 0.0), m(1000.0, 0.0), 30.0))
        assertEquals(5, route.size)
    }
}
