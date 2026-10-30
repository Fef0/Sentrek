package app.sentrek.geo.power

/**
 * When the battery will run out, from how fast it has gone down lately: a straight line
 * through the last hour of readings. Readings are whole percents, so it waits for a long
 * enough stretch and a few percents of drop before saying anything. Charging, or a change
 * of how the phone is used (a power mode), starts it over.
 */
class BatteryForecast {
    private data class Reading(val timeMs: Long, val percent: Int)

    private val readings = ArrayDeque<Reading>()
    private var key: Any? = null

    /** A reading. [key]: what the drain depends on (the power mode); a change starts over. */
    fun add(timeMs: Long, percent: Int, charging: Boolean, key: Any? = null) {
        val last = readings.lastOrNull()
        if (charging || key != this.key || (last != null && (percent > last.percent || timeMs < last.timeMs))) readings.clear()
        this.key = key
        if (charging) return
        readings.addLast(Reading(timeMs, percent))
        while (readings.size > 2 && timeMs - readings.first().timeMs > WINDOW_MS) readings.removeFirst()
    }

    /** When it reaches 0 %, or null while there isn't enough to tell (or it isn't going down). */
    fun emptyAt(): Long? {
        if (readings.size < 2) return null
        val first = readings.first()
        val last = readings.last()
        if (last.timeMs - first.timeMs < MIN_SPAN_MS || first.percent - last.percent < MIN_DROP) return null
        // Least squares: percent against time, in hours to keep the numbers tame.
        val n = readings.size
        val xs = readings.map { (it.timeMs - first.timeMs) / HOUR_MS }
        val ys = readings.map { it.percent.toDouble() }
        val mx = xs.average()
        val my = ys.average()
        var sxy = 0.0
        var sxx = 0.0
        for (i in 0 until n) {
            sxy += (xs[i] - mx) * (ys[i] - my)
            sxx += (xs[i] - mx) * (xs[i] - mx)
        }
        if (sxx <= 0) return null
        val perHour = sxy / sxx
        if (perHour >= 0) return null
        val hoursLeft = last.percent / -perHour
        return last.timeMs + (hoursLeft * HOUR_MS).toLong()
    }

    private companion object {
        const val HOUR_MS = 3_600_000.0
        const val WINDOW_MS = 60 * 60_000L
        const val MIN_SPAN_MS = 20 * 60_000L
        const val MIN_DROP = 3
    }
}
