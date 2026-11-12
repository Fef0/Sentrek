package app.sentrek.geo.plan

import app.sentrek.geo.LatLon
import org.junit.Assert.assertEquals
import org.junit.Test

class SectionsTest {
    private fun p(i: Int, end: Boolean = false) = PlanPoint(LatLon(46.0, 11.0 + i * 0.01), true, end)

    @Test
    fun noMarksNoSections() {
        assertEquals(emptyList<IntRange>(), Sections.legRanges(listOf(p(0), p(1), p(2))))
    }

    @Test
    fun startAndEndDontCount() {
        val points = listOf(p(0, true), p(1), p(2, true))
        assertEquals(emptyList<Int>(), Sections.ends(points))
        assertEquals(emptyList<IntRange>(), Sections.legRanges(points))
    }

    @Test
    fun legsPerSection() {
        // Start, a point, a section end, two more points, another end, the end.
        val points = listOf(p(0), p(1), p(2, true), p(3), p(4), p(5, true), p(6))
        assertEquals(listOf(2, 5), Sections.ends(points))
        assertEquals(listOf(0 until 2, 2 until 5, 5 until 6), Sections.legRanges(points))
    }

    @Test
    fun lastPointMarkedWaitsForTheNext() {
        val points = listOf(p(0), p(1), p(2, true))
        assertEquals(emptyList<IntRange>(), Sections.legRanges(points))
        assertEquals(listOf(0 until 2, 2 until 3), Sections.legRanges(points + p(3)))
    }

    @Test
    fun cutsAlongALine() {
        val line = (0..10).map { LatLon(46.0, 11.0 + it * 0.001) }
        val cuts = Sections.cuts(line, listOf(LatLon(46.0001, 11.0031), LatLon(46.0, 11.0072)))
        assertEquals(listOf(3, 7), cuts)
        val pieces = Sections.pieces(line, cuts)
        assertEquals(listOf(4, 5, 4), pieces.map { it.size })
        assertEquals(line[3], pieces[1].first())
    }

    @Test
    fun outAndBackCutsInOrder() {
        // There and back: the turn is the section end, then the start again.
        val out = (0..5).map { LatLon(46.0, 11.0 + it * 0.001) }
        val line = out + out.reversed().drop(1)
        val cuts = Sections.cuts(line, listOf(out.last()))
        assertEquals(listOf(5), cuts)
    }

    @Test
    fun endPassedAgainOnTheWayBackCutsTheFirstTime() {
        val out = (0..6).map { LatLon(46.0, 11.0 + it * 0.001) }
        // The way back runs 5 m beside the way out, so it passes the end slightly nearer.
        val back = out.reversed().drop(1).map { it.copy(lat = it.lat + 0.00005) }
        val end = LatLon(46.00004, 11.002)
        assertEquals(listOf(2), Sections.cuts(out + back, listOf(end)))
    }

    @Test
    fun reversedPlanKeepsMarksOnTheirPoints() {
        val points = listOf(p(0), p(1, true), p(2), p(3))
        val back = SavedPlan.reversed(points)
        assertEquals(listOf(2), Sections.ends(back))
        assertEquals(points[1].position, back[2].position)
    }
}
