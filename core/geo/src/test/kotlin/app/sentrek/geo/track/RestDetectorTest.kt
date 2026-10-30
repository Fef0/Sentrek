package app.sentrek.geo.track

import app.sentrek.geo.LatLon
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RestDetectorTest {
    private val hut = LatLon(45.93, 7.70)

    /** About [m] metres north of the hut. */
    private fun north(m: Double) = LatLon(hut.lat + m / 111_000, hut.lon)

    @Test
    fun `a stop of three minutes is a rest, GPS wander included`() {
        val d = RestDetector()
        for (s in 0..170 step 10) d.update(north((s % 3) * 8.0), s * 1_000L, 10f)
        assertFalse(d.resting)
        assertTrue(d.update(north(5.0), 180_000, 10f))
        assertTrue(d.resting)
    }

    @Test
    fun `walking away ends it`() {
        val d = RestDetector()
        d.update(hut, 0, 5f)
        d.update(hut, 200_000, 5f)
        assertTrue(d.resting)
        assertTrue(d.update(north(40.0), 210_000, 5f))
        assertFalse(d.resting)
    }

    @Test
    fun `walking never rests`() {
        val d = RestDetector()
        // 1 m/s, a fix every 10 s, for ten minutes.
        for (s in 0..600 step 10) d.update(north(s.toDouble()), s * 1_000L, 5f)
        assertFalse(d.resting)
    }

    @Test
    fun `a wild fix doesn't end a rest`() {
        val d = RestDetector()
        d.update(hut, 0, 5f)
        d.update(hut, 200_000, 5f)
        assertFalse(d.update(north(300.0), 210_000, 80f))
        assertTrue(d.resting)
    }

    @Test
    fun `the motion sensor ends it`() {
        val d = RestDetector()
        d.update(hut, 0, 5f)
        d.update(hut, 200_000, 5f)
        assertTrue(d.moved())
        assertFalse(d.resting)
        // And a new stop takes its three minutes again.
        d.update(hut, 210_000, 5f)
        assertFalse(d.update(hut, 300_000, 5f))
    }
}
