package app.sentrek.geo.track

import app.sentrek.geo.Geo
import app.sentrek.geo.LatLon
import org.junit.Assert.assertEquals
import org.junit.Test

class JumpFilterTest {
    private val start = LatLon(46.0, 7.0)

    /** [metres] east of the start at [seconds]. */
    private fun at(metres: Double, seconds: Int) = TrackPoint(Geo.destination(start, 90.0, metres), seconds * 1000L)

    private fun JumpFilter.feed(point: TrackPoint): JumpFilter.Verdict =
        check(point).also { if (it != JumpFilter.Verdict.Drop) kept(point) }

    @Test
    fun `walking is kept`() {
        val f = JumpFilter()
        for (i in 0..20) assertEquals(JumpFilter.Verdict.Keep, f.feed(at(i * 5.0, i * 4)))
    }

    @Test
    fun `a single bad fix is dropped, the walk goes on`() {
        val f = JumpFilter()
        f.feed(at(0.0, 0))
        f.feed(at(5.0, 4))
        assertEquals(JumpFilter.Verdict.Drop, f.feed(at(2_000.0, 8)))
        assertEquals(JumpFilter.Verdict.Keep, f.feed(at(10.0, 12)))
    }

    @Test
    fun `a new place the fixes agree on starts a new segment`() {
        val f = JumpFilter()
        f.feed(at(0.0, 0))
        assertEquals(JumpFilter.Verdict.Drop, f.feed(at(1_000_000.0, 4)))
        assertEquals(JumpFilter.Verdict.Drop, f.feed(at(1_000_005.0, 8)))
        assertEquals(JumpFilter.Verdict.NewSegment, f.feed(at(1_000_010.0, 12)))
        assertEquals(JumpFilter.Verdict.Keep, f.feed(at(1_000_015.0, 16)))
    }

    @Test
    fun `a long gap is not a jump`() {
        val f = JumpFilter()
        f.feed(at(0.0, 0))
        // Ten minutes in a tunnel, 3 km on.
        assertEquals(JumpFilter.Verdict.Keep, f.feed(at(3_000.0, 600)))
    }
}
