package app.sentrek.geo.route

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PaceTest {
    /** Flat: the standard is 4 km/h, 900 ms a metre. */
    private fun standardLeft(along: Double, length: Double = 20_000.0) = HikingTime.millis(length - along, 0.0, 0.0)

    /** Walks [metres] from [from] at [kmh], a fix every 10 s; returns where and when it ended. */
    private fun PaceTracker.walk(from: Double, startMs: Long, metres: Double, kmh: Double): Pair<Double, Long> {
        val step = kmh / 3.6 * 10
        var along = from
        var t = startMs
        while (along < from + metres) {
            along += step
            t += 10_000
            update(along, standardLeft(along), t, onRoute = true)
        }
        return along to t
    }

    @Test
    fun `learns a slow walker`() {
        val pace = PaceTracker()
        pace.update(0.0, standardLeft(0.0), 0, true)
        pace.walk(0.0, 0, 6_000.0, kmh = 3.0)
        // 3 km/h against the standard 4: a third longer.
        assertEquals(4.0 / 3, pace.factor, 0.05)
        assertTrue(pace.learnt)
    }

    @Test
    fun `starts from the standard and moves away from it`() {
        val pace = PaceTracker()
        assertEquals(1.0, pace.factor, 0.0)
        pace.update(0.0, standardLeft(0.0), 0, true)
        pace.walk(0.0, 0, 1_000.0, kmh = 2.0)
        // A quarter of an hour of standard time at half the speed: partly there.
        assertTrue(pace.factor in 1.2..1.5)
        assertFalse(pace.learnt)
    }

    @Test
    fun `a break doesn't count`() {
        val pace = PaceTracker()
        pace.update(0.0, standardLeft(0.0), 0, true)
        val (along, t) = pace.walk(0.0, 0, 3_000.0, kmh = 4.0)
        // Half an hour at the hut, the GPS wandering a few metres.
        for (i in 1..180) pace.update(along + (i % 3), standardLeft(along), t + i * 10_000L, true)
        pace.walk(along, t + 1_800_000, 3_000.0, kmh = 4.0)
        assertEquals(1.0, pace.factor, 0.05)
    }

    @Test
    fun `off route and backwards don't count`() {
        val pace = PaceTracker()
        pace.update(0.0, standardLeft(0.0), 0, true)
        // Lost for an hour, then back where it left the route, farther on.
        pace.update(500.0, standardLeft(500.0), 600_000, false)
        pace.update(600.0, standardLeft(600.0), 4_200_000, true)
        pace.update(400.0, standardLeft(400.0), 4_300_000, true)
        assertEquals(1.0, pace.factor, 0.0)
    }
}
