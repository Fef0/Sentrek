package app.sentrek.geo.track

import org.junit.Assert.assertEquals
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
}
