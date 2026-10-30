package app.sentrek.geo.power

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class BatteryForecastTest {
    private val minute = 60_000L

    /** [perHour] percents an hour from [start], a reading a minute for [minutes]. */
    private fun BatteryForecast.drain(start: Int, perHour: Double, minutes: Int, from: Long = 0, key: Any? = null) {
        for (m in 0..minutes) add(from + m * minute, (start - perHour * m / 60).toInt(), charging = false, key = key)
    }

    @Test
    fun `a steady drain runs out on time`() {
        val f = BatteryForecast()
        f.drain(80, perHour = 10.0, minutes = 60)
        // 70 % left at 10 % an hour: about 7 hours after the last reading.
        assertEquals(60 * minute + 7 * 60 * minute.toDouble(), f.emptyAt()!!.toDouble(), 20.0 * minute)
    }

    @Test
    fun `nothing too soon`() {
        val f = BatteryForecast()
        f.drain(80, perHour = 10.0, minutes = 10)
        assertNull(f.emptyAt())
        val g = BatteryForecast()
        g.drain(80, perHour = 3.0, minutes = 30)
        assertNull(g.emptyAt())
    }

    @Test
    fun `charging and a new power mode start over`() {
        val f = BatteryForecast()
        f.drain(80, perHour = 10.0, minutes = 40)
        assertNotNull(f.emptyAt())
        f.add(41 * minute, 74, charging = true)
        assertNull(f.emptyAt())
        f.drain(74, perHour = 10.0, minutes = 40, from = 42 * minute)
        assertNotNull(f.emptyAt())
        f.add(83 * minute, 67, charging = false, key = "stamina")
        assertNull(f.emptyAt())
    }

    @Test
    fun `follows the last hour`() {
        val f = BatteryForecast()
        f.drain(90, perHour = 30.0, minutes = 60)
        f.drain(60, perHour = 5.0, minutes = 120, from = 61 * minute)
        // Now 5 % an hour from 50 %: about ten hours, not the earlier pace.
        val left = (f.emptyAt()!! - 181 * minute) / 3_600_000.0
        assertEquals(10.0, left, 1.5)
    }
}
