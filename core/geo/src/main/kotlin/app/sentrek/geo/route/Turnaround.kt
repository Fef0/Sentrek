package app.sentrek.geo.route

/**
 * When to say it's time to turn back, once per walk: when the end of the route comes too
 * close to sunset at the user's pace ([beforeDark]), or at a time of day the user set
 * ([atTime]). Both only say "true" once; the caller rings.
 */
class Turnaround {
    private var darkFired = false
    private var lateSince: Long? = null
    private var timeFired = false
    private var lastCheck: Long? = null

    /**
     * [arrivalMs]: when the user reaches the end at their pace, null when not following a
     * route. True once, when that's later than [marginMs] before [sunsetMs] for a couple of
     * minutes in a row: one odd fix shouldn't ring it.
     */
    fun beforeDark(nowMs: Long, arrivalMs: Long?, sunsetMs: Long?, marginMs: Long): Boolean {
        val late = arrivalMs != null && sunsetMs != null && nowMs < sunsetMs && arrivalMs > sunsetMs - marginMs
        if (!late) {
            lateSince = null
            return false
        }
        val since = lateSince ?: nowMs.also { lateSince = it }
        if (darkFired || nowMs - since < CONFIRM_MS) return false
        darkFired = true
        return true
    }

    /**
     * True once, when [alarmMs] passes while walking: a recording started after it doesn't
     * ring at once.
     */
    fun atTime(nowMs: Long, alarmMs: Long): Boolean {
        val last = lastCheck
        lastCheck = nowMs
        if (timeFired || last == null || last >= alarmMs || nowMs < alarmMs) return false
        timeFired = true
        return true
    }

    private companion object {
        const val CONFIRM_MS = 2 * 60_000L
    }
}
