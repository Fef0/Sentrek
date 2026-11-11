package app.sentrek.geo.track

import app.sentrek.geo.route.HikingTime
import java.util.Calendar
import java.util.TimeZone

/**
 * One walk, as far as the stats care: when it started, how far, how much up and down, how
 * long moving. [id] and [name] say which one a record is.
 */
data class Trip(
    val start: Long,
    val distance: Double,
    val ascent: Double,
    val movingMillis: Long,
    val descent: Double = 0.0,
    val id: String = "",
    val name: String = "",
)

/** Only walks count: a line without moving time (a planned route) isn't one. */
private fun Iterable<Trip>.walked(since: Long, until: Long = Long.MAX_VALUE) =
    filter { it.movingMillis > 0 && it.start >= since && it.start < until }

/** What a set of walks adds up to. */
data class TripTotals(val hikes: Int, val distance: Double, val ascent: Double, val movingMillis: Long) {
    companion object {
        /** The walks started from [since] to before [until]. */
        fun of(trips: Iterable<Trip>, since: Long = Long.MIN_VALUE, until: Long = Long.MAX_VALUE): TripTotals {
            val walked = trips.walked(since, until)
            return TripTotals(
                hikes = walked.size,
                distance = walked.sumOf { it.distance },
                ascent = walked.sumOf { it.ascent },
                movingMillis = walked.sumOf { it.movingMillis },
            )
        }
    }
}

/** The biggest walks of a period: the longest, and the one with the most climb. */
data class TripRecords(val longest: Trip?, val mostClimb: Trip?) {
    companion object {
        fun of(trips: Iterable<Trip>, since: Long = Long.MIN_VALUE, until: Long = Long.MAX_VALUE): TripRecords {
            val walked = trips.walked(since, until)
            return TripRecords(
                longest = walked.maxByOrNull { it.distance },
                mostClimb = walked.filter { it.ascent > 0 }.maxByOrNull { it.ascent },
            )
        }
    }
}

/** Distance walked per month of a year, or per year: the bars of the stats. */
object TripPeriods {
    /** Metres walked in each month (0 = January) of [year], in [zone]. */
    fun byMonth(trips: Iterable<Trip>, year: Int, zone: TimeZone = TimeZone.getDefault()): DoubleArray {
        val months = DoubleArray(12)
        val calendar = Calendar.getInstance(zone)
        for (trip in trips.walked(Long.MIN_VALUE)) {
            calendar.timeInMillis = trip.start
            if (calendar.get(Calendar.YEAR) == year) months[calendar.get(Calendar.MONTH)] += trip.distance
        }
        return months
    }

    /**
     * The running total through [year]: (day of the year from 0, metres walked so far) after
     * each day with a walk, from (0, 0). A line of these says how the year went, and laid on
     * last year's, whether this one is ahead.
     */
    fun cumulative(trips: Iterable<Trip>, year: Int, zone: TimeZone = TimeZone.getDefault()): List<Pair<Int, Double>> {
        val calendar = Calendar.getInstance(zone)
        val byDay = sortedMapOf<Int, Double>()
        for (trip in trips.walked(Long.MIN_VALUE)) {
            calendar.timeInMillis = trip.start
            if (calendar.get(Calendar.YEAR) != year) continue
            val day = calendar.get(Calendar.DAY_OF_YEAR) - 1
            byDay[day] = (byDay[day] ?: 0.0) + trip.distance
        }
        var total = 0.0
        return listOf(0 to 0.0) + byDay.map { (day, m) -> total += m; day to total }
    }

    /**
     * Metres walked in each year, from the first walk's to [lastYear], years without walks
     * included (as 0): the gaps are part of the story. Empty without walks.
     */
    fun byYear(trips: Iterable<Trip>, lastYear: Int, zone: TimeZone = TimeZone.getDefault()): List<Pair<Int, Double>> {
        val calendar = Calendar.getInstance(zone)
        val totals = HashMap<Int, Double>()
        for (trip in trips.walked(Long.MIN_VALUE)) {
            calendar.timeInMillis = trip.start
            val year = calendar.get(Calendar.YEAR)
            totals[year] = (totals[year] ?: 0.0) + trip.distance
        }
        val first = totals.keys.minOrNull() ?: return emptyList()
        return (first..maxOf(first, lastYear)).map { it to (totals[it] ?: 0.0) }
    }
}

/**
 * How the user walks compared with signpost times (the standard estimate, [HikingTime]):
 * their moving time over the estimate for the same walks. 0.85 is 15% faster than the
 * signposts. Both leave out the stops, so they compare.
 */
object PersonalPace {
    /** Walks long enough to say something about a pace, oldest first. */
    fun usableWalks(trips: Iterable<Trip>): List<Trip> = trips.usable().sortedBy { it.start }

    /** Walks needed before it's said: one walk could be a race or a stroll. */
    const val MIN_WALKS = 3

    /** Shorter walks say little about a pace: an hour on a viewpoint path isn't a hike. */
    private const val MIN_DISTANCE_M = 2_000.0
    private const val MIN_MOVING_MS = 30 * 60_000L

    private fun Iterable<Trip>.usable() = filter { it.movingMillis >= MIN_MOVING_MS && it.distance >= MIN_DISTANCE_M }

    /**
     * Each walk long enough to tell, oldest first, with its own factor: dots that show
     * whether the user is getting quicker, or slower on the steep ones.
     */
    fun perWalk(trips: Iterable<Trip>): List<Pair<Trip, Double>> =
        trips.usable().sortedBy { it.start }.mapNotNull { trip ->
            val standard = HikingTime.millis(trip.distance, trip.ascent, trip.descent)
            if (standard <= 0) null else trip to (trip.movingMillis.toDouble() / standard).coerceIn(0.4, 2.5)
        }

    /** The factor from the user's walks, or null with too few to tell. */
    fun factor(trips: Iterable<Trip>): Double? {
        val usable = trips.usable()
        if (usable.size < MIN_WALKS) return null
        val standard = usable.sumOf { HikingTime.millis(it.distance, it.ascent, it.descent) }
        if (standard <= 0) return null
        // Bounded: a GPS gone wild on one walk mustn't make every route's time absurd.
        return (usable.sumOf { it.movingMillis }.toDouble() / standard).coerceIn(0.4, 2.5)
    }
}
