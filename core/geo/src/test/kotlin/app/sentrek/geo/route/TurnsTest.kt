package app.sentrek.geo.route

import app.sentrek.geo.Geo
import app.sentrek.geo.LatLon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos

class TurnsTest {
    /** A point [east] and [north] metres from 46°N 7°E. */
    private fun m(east: Double, north: Double): LatLon {
        val ky = Geo.EARTH_RADIUS_M * Math.toRadians(1.0)
        return LatLon(46.0 + north / ky, 7.0 + east / (ky * cos(Math.toRadians(46.0))))
    }

    private fun line(vararg xy: Pair<Double, Double>) = xy.map { (x, y) -> m(x, y) }

    @Test
    fun `turning off a path at a junction is a turn`() {
        // East along a path that carries on east; the route turns north at 500 m.
        val main = line(0.0 to 0.0, 500.0 to 0.0, 1000.0 to 0.0)
        val side = line(500.0 to 0.0, 500.0 to 500.0)
        val route = Route(line(0.0 to 0.0, 500.0 to 0.0, 500.0 to 500.0))
        val turns = Turns.find(route, listOf(main, side))
        assertEquals(1, turns.size)
        assertEquals(500.0, turns[0], 15.0)
    }

    @Test
    fun `going straight across a crossroads is not a turn`() {
        val main = line(0.0 to 0.0, 1000.0 to 0.0)
        val cross = line(500.0 to -300.0, 500.0 to 0.0, 500.0 to 300.0)
        val route = Route(line(0.0 to 0.0, 1000.0 to 0.0))
        assertTrue(Turns.find(route, listOf(main, cross)).isEmpty())
    }

    @Test
    fun `a bend with no other path is not a turn`() {
        val bend = line(0.0 to 0.0, 500.0 to 0.0, 500.0 to 500.0)
        val route = Route(bend)
        assertTrue(Turns.find(route, listOf(bend)).isEmpty())
    }

    @Test
    fun `taking the less straight way at a fork is a turn`() {
        // At 500 m one path goes on almost straight, the route bears off 30° on the other.
        val straight = line(0.0 to 0.0, 500.0 to 0.0, 1000.0 to 20.0)
        val bear = line(500.0 to 0.0, 933.0 to 250.0)
        val route = Route(line(0.0 to 0.0, 500.0 to 0.0, 933.0 to 250.0))
        val turns = Turns.find(route, listOf(straight, bear))
        assertEquals(1, turns.size)
        // Found where the two paths part, a little past the fork itself.
        assertEquals(510.0, turns[0], 20.0)
    }

    @Test
    fun `taking the straighter way at a fork is not a turn`() {
        val straight = line(0.0 to 0.0, 500.0 to 0.0, 1000.0 to 20.0)
        val bear = line(500.0 to 0.0, 933.0 to 250.0)
        val route = Route(line(0.0 to 0.0, 500.0 to 0.0, 1000.0 to 20.0))
        assertTrue(Turns.find(route, listOf(straight, bear)).isEmpty())
    }

    @Test
    fun `a GPS-recorded route a few metres off the path still finds the junction`() {
        val main = line(0.0 to 0.0, 1000.0 to 0.0)
        val side = line(500.0 to 0.0, 500.0 to 500.0)
        val route = Route(line(0.0 to 6.0, 250.0 to -5.0, 497.0 to 7.0, 506.0 to 250.0, 494.0 to 500.0))
        assertEquals(1, Turns.find(route, listOf(main, side)).size)
    }

    @Test
    fun `no lines, no turns`() {
        assertTrue(Turns.find(Route(line(0.0 to 0.0, 500.0 to 0.0, 500.0 to 500.0)), emptyList()).isEmpty())
    }
}
