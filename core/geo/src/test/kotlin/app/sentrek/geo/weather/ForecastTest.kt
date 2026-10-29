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
