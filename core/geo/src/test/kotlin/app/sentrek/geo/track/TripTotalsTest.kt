package app.sentrek.geo.track

import app.sentrek.geo.route.HikingTime
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import java.util.Calendar
import java.util.TimeZone
import org.junit.Test

class TripTotalsTest {
    private val trips = listOf(
        Trip(start = 100, distance = 12_000.0, ascent = 800.0, movingMillis = 4 * 3_600_000L),
        Trip(start = 200, distance = 8_000.0, ascent = 450.0, movingMillis = 3 * 3_600_000L),
        // A planned route: no times, not a walk.
        Trip(start = 300, distance = 20_000.0, ascent = 1_500.0, movingMillis = 0),
        Trip(start = 50, distance = 5_000.0, ascent = 100.0, movingMillis = 3_600_000L),
    )

    @Test
    fun `adds up the walks, not the plans`() {
        assertEquals(TripTotals(3, 25_000.0, 1_350.0, 8 * 3_600_000L), TripTotals.of(trips))
    }

    @Test
    fun `counts only walks since a date`() {
        assertEquals(TripTotals(2, 20_000.0, 1_250.0, 7 * 3_600_000L), TripTotals.of(trips, since = 100))
        assertEquals(TripTotals(0, 0.0, 0.0, 0), TripTotals.of(trips, since = 1_000))
    }

    @Test
    fun `records are the longest walk and the one with most climb, plans left out`() {
        val records = TripRecords.of(trips)
        assertEquals(12_000.0, records.longest!!.distance, 0.0)
        assertEquals(800.0, records.mostClimb!!.ascent, 0.0)
        assertNull(TripRecords.of(trips, since = 1_000).longest)
    }

    private val utc = TimeZone.getTimeZone("UTC")

    private fun at(year: Int, month: Int, day: Int): Long = Calendar.getInstance(utc).apply {
        clear()
        set(year, month, day, 9, 0)
    }.timeInMillis

    private fun walk(start: Long, km: Double) = Trip(start, km * 1000, 0.0, 3_600_000L)

    @Test
    fun `distance per month of one year`() {
        val walks = listOf(
            walk(at(2026, Calendar.MARCH, 3), 10.0),
            walk(at(2026, Calendar.MARCH, 20), 5.0),
            walk(at(2026, Calendar.DECEMBER, 31), 7.0),
            walk(at(2025, Calendar.MARCH, 3), 99.0), // Another year.
            Trip(at(2026, Calendar.JUNE, 1), 20_000.0, 0.0, 0), // A plan.
        )
        val expected = DoubleArray(12).apply {
            this[Calendar.MARCH] = 15_000.0
            this[Calendar.DECEMBER] = 7_000.0
        }
        assertArrayEquals(expected, TripPeriods.byMonth(walks, 2026, utc), 0.0)
    }

    @Test
    fun `distance per year, empty years kept`() {
        val walks = listOf(walk(at(2023, 5, 1), 10.0), walk(at(2026, 5, 1), 4.0), walk(at(2026, 7, 1), 6.0))
        assertEquals(listOf(2023 to 10_000.0, 2024 to 0.0, 2025 to 0.0, 2026 to 10_000.0), TripPeriods.byYear(walks, 2026, utc))
        assertEquals(emptyList<Pair<Int, Double>>(), TripPeriods.byYear(emptyList(), 2026, utc))
    }

    @Test
    fun `pace against the signposts, once there are enough walks`() {
        fun hike(factor: Double): Trip {
            val standard = HikingTime.millis(10_000.0, 600.0, 600.0)
            return Trip(0, 10_000.0, 600.0, (standard * factor).toLong(), descent = 600.0)
        }
        assertNull(PersonalPace.factor(listOf(hike(0.8), hike(0.8))))
        assertEquals(0.8, PersonalPace.factor(listOf(hike(0.8), hike(0.8), hike(0.8)))!!, 0.001)
        // Short strolls don't count towards it.
        val stroll = Trip(0, 800.0, 0.0, 20 * 60_000L)
        assertNull(PersonalPace.factor(listOf(hike(0.8), hike(0.8), stroll)))
        // A wild one is bounded.
        assertEquals(2.5, PersonalPace.factor(listOf(hike(9.0), hike(9.0), hike(9.0)))!!, 0.0)
    }

    @Test
    fun `running total of a year, one point per day walked`() {
        val walks = listOf(
            walk(at(2026, Calendar.JANUARY, 10), 5.0),
            walk(at(2026, Calendar.JANUARY, 10), 3.0), // Same day: one step.
            walk(at(2026, Calendar.MARCH, 1), 10.0),
            walk(at(2025, Calendar.MARCH, 1), 99.0),
        )
        assertEquals(listOf(0 to 0.0, 9 to 8_000.0, 59 to 18_000.0), TripPeriods.cumulative(walks, 2026, utc))
        assertEquals(listOf(0 to 0.0), TripPeriods.cumulative(walks, 2024, utc))
    }

    @Test
    fun `pace of each walk, oldest first`() {
        val standard = HikingTime.millis(10_000.0, 0.0, 0.0)
        val late = Trip(200, 10_000.0, 0.0, (standard * 0.9).toLong(), id = "b")
        val early = Trip(100, 10_000.0, 0.0, (standard * 1.1).toLong(), id = "a")
        val stroll = Trip(150, 500.0, 0.0, 10 * 60_000L)
        val each = PersonalPace.perWalk(listOf(late, stroll, early))
        assertEquals(listOf("a", "b"), each.map { it.first.id })
        assertEquals(1.1, each[0].second, 0.001)
        assertEquals(0.9, each[1].second, 0.001)
    }

    @Test
    fun `totals and records of one period only`() {
        // Started from 100 to before 300: the walks at 100 and 200, not the one at 50.
        assertEquals(TripTotals(2, 20_000.0, 1_250.0, 7 * 3_600_000L), TripTotals.of(trips, since = 100, until = 300))
        assertEquals(TripTotals(1, 12_000.0, 800.0, 4 * 3_600_000L), TripTotals.of(trips, since = 100, until = 200))
        assertEquals(8_000.0, TripRecords.of(trips, since = 150, until = 300).longest!!.distance, 0.0)
    }
}
