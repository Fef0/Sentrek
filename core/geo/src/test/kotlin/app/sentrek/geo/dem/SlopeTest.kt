package app.sentrek.geo.dem

import org.junit.Assert.assertEquals
import org.junit.Test

class SlopeTest {
    /** A plane rising [rise] metres per [pixel] metres eastwards, 4 × 4 with its border. */
    private fun plane(rise: Float) = FloatArray(16) { (it % 4) * rise }

    @Test
    fun `flat ground has no slope`() {
        val slope = Slope.degrees(FloatArray(16) { 1200f }, 4, 4) { 30.0 }
        assertEquals(4, slope.size)
        slope.forEach { assertEquals(0f, it, 0.001f) }
    }

    @Test
    fun `rising as much as it runs is 45 degrees`() {
        Slope.degrees(plane(30f), 4, 4) { 30.0 }.forEach { assertEquals(45f, it, 0.01f) }
        Slope.degrees(plane(15f), 4, 4) { 30.0 }.forEach { assertEquals(26.57f, it, 0.01f) }
    }

    @Test
    fun `bands start at 30 degrees`() {
        assertEquals(0, Slope.band(29.9f))
        assertEquals(1, Slope.band(30f))
        assertEquals(2, Slope.band(37f))
        assertEquals(3, Slope.band(44.9f))
        assertEquals(4, Slope.band(60f))
    }

    @Test
    fun `terrain pixels shrink towards the poles`() {
        assertEquals(38.2, Slope.metersPerPixel(0.0, 12), 0.1)
        assertEquals(30.2, Slope.metersPerPixel(37.75, 12), 0.1)
    }
}
