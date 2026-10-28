package app.sentrek.geo.dem

import app.sentrek.geo.units.ElevationUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

class ContoursTest {
    /** A cone peaking at [peak] metres in the middle of a [size]² grid, falling 10 m per cell. */
    private fun cone(size: Int, peak: Float): FloatArray {
        val c = (size - 1) / 2.0
        return FloatArray(size * size) { (peak - 10 * hypot(it % size - c, it / size - c)).toFloat() }
    }

    @Test
    fun `a peak gives one closed ring per level`() {
        val lines = Contours.trace(cone(21, 1_095f), 21, 21, 20)
        // Within the grid's inscribed circle (995 m): whole rings at 1000, 1020, 1040, 1060, 1080.
        val rings = lines.filter { it.level >= 1_000 }
        assertEquals(listOf(1_000, 1_020, 1_040, 1_060, 1_080), rings.map { it.level }.sorted())
        assertTrue(rings.all { it.closed })
        // The 1080 m ring is 1.5 cells from the peak all round.
        val ring = lines.first { it.level == 1_080 }
        for (p in 0 until ring.xy.size / 2) {
            assertEquals(1.5, hypot(ring.xy[p * 2] - 10, ring.xy[p * 2 + 1] - 10), 0.2)
        }
    }

    @Test
    fun `a slope gives open lines crossing the grid`() {
        // Rising 10 m per column: 105 m at the left, 195 m at the right.
        val heights = FloatArray(10 * 5) { 105f + 10 * (it % 10) }
        val lines = Contours.trace(heights, 10, 5, 50)
        assertEquals(listOf(150), lines.map { it.level })
        val line = lines.first { it.level == 150 }
        assertFalse(line.closed)
        // Straight down between columns 4 and 5, top to bottom.
        assertEquals(5, line.xy.size / 2)
        for (p in 0 until 5) assertEquals(4.5, line.xy[p * 2], 1e-9)
    }

    @Test
    fun `nothing at sea level or below`() {
        val heights = FloatArray(4 * 4) { -30f + 10 * (it % 4) }
        assertTrue(Contours.trace(heights, 4, 4, 10).isEmpty())
    }

    @Test
    fun `smoothing keeps the ends and closes rings`() {
        val open = Contours.smooth(Contours.Line(10, doubleArrayOf(0.0, 0.0, 1.0, 0.0, 1.0, 1.0)))
        assertEquals(0.0, open.xy[0], 0.0)
        assertEquals(1.0, open.xy[open.xy.size - 1], 0.0)
        val ring = Contours.smooth(Contours.Line(10, doubleArrayOf(0.0, 0.0, 1.0, 0.0, 1.0, 1.0, 0.0, 0.0)))
        assertTrue(ring.closed)
    }

    @Test
    fun `spacing follows zoom and unit`() {
        assertEquals(Contours.Spacing(10, 50), Contours.spacing(16, ElevationUnit.Meters))
        assertEquals(Contours.Spacing(100, 500), Contours.spacing(12, ElevationUnit.Meters))
        assertEquals(Contours.Spacing(40, 200), Contours.spacing(15, ElevationUnit.Feet))
    }
}
