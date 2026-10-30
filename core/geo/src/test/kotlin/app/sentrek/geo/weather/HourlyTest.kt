package app.sentrek.geo.weather

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HourlyTest {
    private val hour = Hourly.HOUR_MS

    /** Hours from 0: [wet] says which are wet and how. */
    private fun forecast(vararg wet: Pair<Int, Hourly.Wet>): Hourly {
        val by = wet.toMap()
        return Hourly((0 until 12).map { h ->
            when (by[h]) {
                Hourly.Wet.Storm -> Hourly.Hour(h * hour, 40, 2.0, Forecast.Sky.Storm)
                Hourly.Wet.Rain -> Hourly.Hour(h * hour, 80, 1.5, Forecast.Sky.Rain)
                Hourly.Wet.Snow -> Hourly.Hour(h * hour, 70, 1.0, Forecast.Sky.Snow)
                null -> Hourly.Hour(h * hour, 10, 0.0, Forecast.Sky.Clear)
            }
        })
    }

    @Test
    fun `parses one place and several`() {
        val one = """{"hourly":{"time":[0,3600],"precipitation_probability":[10,90],"precipitation":[0,2.5],"weather_code":[1,95]}}"""
        val parsed = Hourly.parse(one.reader())!!
        assertEquals(1, parsed.size)
        assertEquals(Hourly.Wet.Storm, parsed[0].at(3_600_000 + 10)!!.wet)
        assertNull(parsed[0].at(10)!!.wet)
        val two = "[$one,$one]"
        assertEquals(2, Hourly.parse(two.reader())!!.size)
    }

    @Test
    fun `unlikely or light rain is dry`() {
        assertNull(Hourly.Hour(0, 30, 3.0, Forecast.Sky.Rain).wet)
        assertNull(Hourly.Hour(0, 90, 0.1, Forecast.Sky.Drizzle).wet)
        assertEquals(Hourly.Wet.Rain, Hourly.Hour(0, 60, 0.5, Forecast.Sky.Showers).wet)
    }

    @Test
    fun `rain where the user will be, not where they are`() {
        // Dry here now, rain from 3 h on everywhere: the ridge reached at 4 h gets it.
        val f = forecast(3 to Hourly.Wet.Rain, 4 to Hourly.Wet.Rain, 5 to Hourly.Wet.Rain)
        val found = WetAhead.first(
            listOf(
                WetAhead.Sample(0.0, 0, 1000.0, f),
                WetAhead.Sample(4_000.0, 2 * hour, 1500.0, f),
                WetAhead.Sample(8_000.0, 4 * hour, 2450.0, f),
            ),
            now = 0,
        )!!
        assertEquals(Hourly.Wet.Rain, found.wet)
        assertEquals(8_000.0, found.ahead, 0.0)
        assertEquals(4 * hour, found.from)
    }

    @Test
    fun `dry all the way`() {
        val f = forecast(9 to Hourly.Wet.Storm)
        assertNull(WetAhead.first(listOf(WetAhead.Sample(0.0, 0, null, f), WetAhead.Sample(5_000.0, 2 * hour, null, f)), now = 0))
    }

    @Test
    fun `here, within a few hours`() {
        assertEquals(2 * hour, WetAhead.here(forecast(2 to Hourly.Wet.Storm), now = 30 * 60_000L)!!.from)
        assertNull(WetAhead.here(forecast(6 to Hourly.Wet.Rain), now = 0))
    }
}
