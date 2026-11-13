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

    /** Two ways from (0,0) to (1000,0): straight across a T5 ridge, or around through a T2 valley 1400 m long. */
    private fun ridgeAndValley(): TrailGraph = TrailGraph(
        listOf(
            TrailLine(listOf(m(0.0, 0.0), m(500.0, 0.0), m(1000.0, 0.0)), grade = 5),
            TrailLine(listOf(m(0.0, 0.0), m(200.0, -400.0), m(800.0, -400.0), m(1000.0, 0.0)), grade = 2),
        ),
    )

    @Test
    fun `easiest keeps to the lower grade`() {
        val graph = ridgeAndValley()
        val shortest = must(graph.route(m(0.0, 0.0), m(1000.0, 0.0), 30.0))
        assertEquals(1000.0, Geo.length(shortest), 2.0)
        val easiest = must(graph.route(m(0.0, 0.0), m(1000.0, 0.0), 30.0, RouteOptions(RouteOptions.Profile.Easiest)))
        assertTrue(Geo.length(easiest) > 1300)
    }

    @Test
    fun `least climb goes round the hill`() {
        // The direct way crosses a 200 m high point; the way round stays flat.
        val graph = TrailGraph(
            listOf(
                TrailLine(listOf(m(0.0, 0.0), m(500.0, 0.0), m(1000.0, 0.0))),
                TrailLine(listOf(m(0.0, 0.0), m(200.0, -400.0), m(800.0, -400.0), m(1000.0, 0.0))),
            ),
        )
        val heights = graph.nodes.map { if (Geo.distance(it, m(500.0, 0.0)) < 1) 200.0 else 0.0 }.toDoubleArray()
        val direct = must(graph.route(m(0.0, 0.0), m(1000.0, 0.0), 30.0, RouteOptions(), heights))
        assertEquals(1000.0, Geo.length(direct), 2.0)
        val flat = must(graph.route(m(0.0, 0.0), m(1000.0, 0.0), 30.0, RouteOptions(RouteOptions.Profile.LeastClimb), heights))
        assertTrue(Geo.length(flat) > 1300)
    }

    @Test
    fun `via ferratas and avoided spots are left out`() {
        val graph = TrailGraph(
            listOf(
                TrailLine(listOf(m(0.0, 0.0), m(1000.0, 0.0)), cost = 3.0, ferrata = true),
                TrailLine(listOf(m(0.0, 0.0), m(500.0, 2000.0), m(1000.0, 0.0))),
            ),
        )
        // 1000 m of via ferrata at 3 per metre still beats 4100 m of path.
        assertEquals(1000.0, Geo.length(must(graph.route(m(0.0, 0.0), m(1000.0, 0.0), 30.0))), 2.0)
        val noFerrata = must(graph.route(m(0.0, 0.0), m(1000.0, 0.0), 30.0, RouteOptions(avoidFerratas = true)))
        assertTrue(Geo.length(noFerrata) > 4000)
        // The path round closed too: no way at all.
        assertNull(graph.route(m(0.0, 0.0), m(1000.0, 0.0), 30.0, RouteOptions(avoidFerratas = true, avoided = listOf(m(500.0, 2000.0)))))
        // Closing the via ferrata in the middle does the same as avoiding it.
        val closed = must(graph.route(m(0.0, 0.0), m(1000.0, 0.0), 30.0, RouteOptions(avoided = listOf(m(500.0, 10.0)))))
        assertTrue(Geo.length(closed) > 4000)
    }

    @Test
    fun `other ways are really other ways`() {
        // Three parallel ways: 1000, about 1180 and 1430 m long, and one far too long.
        val lines = listOf(0.0, 150.0, 300.0, 3000.0).map { off ->
            TrailLine(listOf(m(0.0, 0.0), m(100.0, off), m(900.0, off), m(1000.0, 0.0)))
        }
        val ways = TrailGraph(lines).alternatives(m(0.0, 0.0), m(1000.0, 0.0), 30.0)
        assertEquals(3, ways.size)
        val lengths = ways.map { Geo.length(it) }
        assertEquals(lengths.sorted(), lengths)
        assertTrue(lengths.last() < 1700)
    }

    @Test
    fun `roads are taken when shorter, unless avoided`() {
        val road = TrailLine(listOf(m(0.0, 0.0), m(1000.0, 0.0)), road = true)
        val trail = TrailLine(listOf(m(0.0, 0.0), m(100.0, 300.0), m(900.0, 300.0), m(1000.0, 0.0)))
        val graph = TrailGraph(listOf(road, trail))
        assertEquals(1000.0, Geo.length(must(graph.route(m(0.0, 0.0), m(1000.0, 0.0), 30.0))), 2.0)
        val paths = must(graph.route(m(0.0, 0.0), m(1000.0, 0.0), 30.0, RouteOptions(avoidRoads = true)))
        assertTrue(Geo.length(paths) > 1100)
    }
}
