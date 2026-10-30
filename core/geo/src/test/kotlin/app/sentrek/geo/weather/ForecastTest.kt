package app.sentrek.geo.weather

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ForecastTest {
    @Test
    fun `reads the daily forecast`() {
        val json = """{"latitude":37.75,"longitude":15.0,"elevation":2450.0,"daily_units":{},
            "daily":{"time":["2026-10-29","2026-10-30"],"weather_code":[95,1],
            "temperature_2m_max":[8.4,11.0],"temperature_2m_min":[-1.2,null],
            "precipitation_sum":[12.5,0.0],"precipitation_probability_max":[90,5],
            "wind_speed_10m_max":[9.0,3.1],"wind_gusts_10m_max":[21.3,6.0]}}"""
        val forecast = Forecast.parse(json.reader())!!
        assertEquals(2450.0, forecast.elevation!!, 0.0)
        val (storm, fine) = forecast.days
        assertEquals("2026-10-29", storm.date)
        assertEquals(Forecast.Sky.Storm, storm.sky)
        assertTrue(storm.storm)
        assertTrue(storm.windy)
        assertEquals(90, storm.precipitationChance)
        assertEquals(Forecast.Sky.Clear, fine.sky)
        assertNull(fine.minTemp)
        assertFalse(fine.windy)
    }

    @Test
    fun `reads the hours of each day`() {
        val json = """{"elevation":1200.0,
            "daily":{"time":["2026-10-30"],"weather_code":[61]},
            "hourly":{"time":["2026-10-30T13:00","2026-10-30T14:00","2026-10-31T00:00"],"weather_code":[2,61,0],
            "temperature_2m":[9.5,7.0,1.0],"precipitation":[0.0,1.8,0.0],"precipitation_probability":[10,80,0],
            "wind_speed_10m":[3.0,6.5,1.0],"wind_gusts_10m":[7.0,14.2,2.0]}}"""
        val forecast = Forecast.parse(json.reader())!!
        val hours = forecast.hoursOf("2026-10-30")
        assertEquals(listOf(13, 14), hours.map { it.hour })
        assertEquals(Forecast.Sky.Rain, hours[1].sky)
        assertEquals(80, hours[1].precipitationChance)
        assertEquals(14.2, hours[1].gusts!!, 0.0)
        // Saved before there were hours: none, and no harm.
        assertTrue(Forecast.parse("""{"daily":{"time":["2026-10-30"],"weather_code":[1]}}""".reader())!!.hours.isEmpty())
    }

    @Test
    fun `an error answer has no forecast`() {
        assertNull(Forecast.parse("""{"error":true,"reason":"Latitude must be in range"}""".reader()))
        assertNull(Forecast.parse("not json".reader()))
    }

    @Test
    fun `the request is rounded`() {
        val url = Forecast.url(37.75432, 14.99871, 2447.0)
        assertTrue(url.contains("latitude=37.75&longitude=15.00&elevation=2450&"))
        assertTrue(url.contains("forecast_days=7"))
        assertFalse(Forecast.url(1.0, 2.0, null).contains("elevation"))
    }
}
