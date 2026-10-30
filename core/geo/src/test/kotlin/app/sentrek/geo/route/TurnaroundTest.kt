package app.sentrek.geo.route

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TurnaroundTest {
    private val minute = 60_000L
    private val sunset = 600 * minute

    @Test
    fun `rings once when the end comes after dark`() {
        val t = Turnaround()
        assertFalse(t.beforeDark(0, sunset - 60 * minute, sunset, 30 * minute))
        assertFalse(t.beforeDark(minute, sunset + 10 * minute, sunset, 30 * minute))
        assertTrue(t.beforeDark(4 * minute, sunset + 10 * minute, sunset, 30 * minute))
        assertFalse(t.beforeDark(5 * minute, sunset + 10 * minute, sunset, 30 * minute))
    }

    @Test
    fun `the margin counts`() {
        val t = Turnaround()
        // 10 minutes before sunset, with half an hour wanted.
        t.beforeDark(0, sunset - 10 * minute, sunset, 30 * minute)
        assertTrue(t.beforeDark(3 * minute, sunset - 10 * minute, sunset, 30 * minute))
    }

    @Test
    fun `one late estimate doesn't ring`() {
        val t = Turnaround()
        t.beforeDark(0, sunset + minute, sunset, 0)
        t.beforeDark(minute, sunset - 30 * minute, sunset, 0)
        assertFalse(t.beforeDark(3 * minute, sunset + minute, sunset, 0))
    }

    @Test
    fun `nothing without a route or after sunset`() {
        val t = Turnaround()
        t.beforeDark(0, null, sunset, 0)
        assertFalse(t.beforeDark(5 * minute, null, sunset, 0))
        t.beforeDark(sunset + minute, sunset + 60 * minute, sunset, 0)
        assertFalse(t.beforeDark(sunset + 5 * minute, sunset + 60 * minute, sunset, 0))
    }

    @Test
    fun `rings once as the set time passes`() {
        val t = Turnaround()
        val at = 840 * minute
        assertFalse(t.atTime(at - 2 * minute, at))
        assertFalse(t.atTime(at - minute, at))
        assertTrue(t.atTime(at + 10_000, at))
        assertFalse(t.atTime(at + minute, at))
    }

    @Test
    fun `not when walking starts after the set time`() {
        val t = Turnaround()
        val at = 840 * minute
        assertFalse(t.atTime(at + minute, at))
        assertFalse(t.atTime(at + 2 * minute, at))
    }
}
