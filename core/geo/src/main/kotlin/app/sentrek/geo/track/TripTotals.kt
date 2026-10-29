package app.sentrek.geo.track

/** One walk, as far as the totals care: when it started, how far, how much up, how long moving. */
data class Trip(val start: Long, val distance: Double, val ascent: Double, val movingMillis: Long)

/** What a set of walks adds up to. */
data class TripTotals(val hikes: Int, val distance: Double, val ascent: Double, val movingMillis: Long) {
    companion object {
        /**
         * The walks started at [since] or later. Planned routes have no moving time and aren't
         * walks: they don't count.
         */
        fun of(trips: Iterable<Trip>, since: Long = Long.MIN_VALUE): TripTotals {
            val walked = trips.filter { it.movingMillis > 0 && it.start >= since }
            return TripTotals(
                hikes = walked.size,
                distance = walked.sumOf { it.distance },
                ascent = walked.sumOf { it.ascent },
                movingMillis = walked.sumOf { it.movingMillis },
            )
        }
    }
}
