package app.sentrek.geo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class SunTest {
    private fun at(iso: String) = Instant.parse(iso).toEpochMilli()

    /** The sunrise equation is good to a minute or two; the times below are rounded. */
    private val tolerance = 3 * 60_000.0

    @Test
    fun `zermatt in late october`() {
        // 28 Oct 2026, 46.02 N 7.75 E: sunrise about 07:01, sunset about 17:22 CET.
        val day = Sun.day(at("2026-10-28T12:00:00Z"), 46.02, 7.75)!!
        assertEquals(at("2026-10-28T06:01:00Z").toDouble(), day.sunrise.toDouble(), tolerance)
        assertEquals(at("2026-10-28T16:22:00Z").toDouble(), day.sunset.toDouble(), tolerance)
    }

    @Test
    fun `the same day all day long, west of greenwich too`() {
        // Denver, 21 June: sunset about 20:31 MDT, already the next calendar day in UTC.
        val morning = Sun.day(at("2026-06-21T14:00:00Z"), 39.74, -104.99)!!
        val evening = Sun.day(at("2026-06-22T01:00:00Z"), 39.74, -104.99)!!
        assertEquals(morning, evening)
        assertEquals(at("2026-06-22T02:31:00Z").toDouble(), morning.sunset.toDouble(), tolerance)
    }

    @Test
    fun `no sunset in the midnight sun`() {
        assertNull(Sun.day(at("2026-06-21T12:00:00Z"), 69.65, 18.96))
    }
}
