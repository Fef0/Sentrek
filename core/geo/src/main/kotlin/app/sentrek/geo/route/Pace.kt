package app.sentrek.geo.route

import kotlin.math.max

/**
 * How the user walks against the standard hiking time ([HikingTime]): 1.2 takes a fifth
 * longer, 0.8 a fifth less. The standard fits an average hiker; people differ by half or
 * more, and a time left that's too short is how a hike ends in the dark.
 */
object Pace {
    const val MIN = 0.5
    const val MAX = 2.5
}

/**
 * Learns the user's pace while following a route: the time it actually takes to cover a
 * stretch against the standard time for it. Breaks, going back and walking off the route
 * don't count. It starts from the standard, and the user's own pace takes over as it's
 * walked: half of it after a little over twenty minutes, all of it after three quarters
 * of an hour.
 */
class PaceTracker {
    private var actualMs = 0L
    private var standardMs = 0L

    private var markAlong: Double? = null
    private var markTime = 0L
    private var markStandardLeft = 0L

    /** The pace to scale the standard time by: the learnt one, blended in as it grows. */
    val factor: Double
        get() {
            if (standardMs <= 0) return 1.0
            val learnt = (actualMs.toDouble() / standardMs).coerceIn(Pace.MIN, Pace.MAX)
            val weight = (standardMs.toDouble() / FULL_WEIGHT_MS).coerceAtMost(1.0)
            return (1 - weight) + learnt * weight
        }

    /** Whether the pace is more the user's own than the standard's. */
    val learnt: Boolean get() = standardMs >= FULL_WEIGHT_MS / 2

    /**
     * A fix [along] metres down the route at [timeMs], with [standardLeftMs] of standard time
     * still to go from there; [onRoute] false while off it.
     */
    fun update(along: Double, standardLeftMs: Long, timeMs: Long, onRoute: Boolean) {
        val m = markAlong
        if (!onRoute || m == null || along < m - STEP_M) return mark(along, timeMs, standardLeftMs, onRoute)
        if (along - m < STEP_M) {
            // Hardly moving for long: a break. It starts over from here, uncounted.
            if (timeMs - markTime > BREAK_MS) mark(along, timeMs, standardLeftMs, true)
            return
        }
        val took = timeMs - markTime
        val standard = markStandardLeft - standardLeftMs
        // Much longer than even a slow walker needs: there was a break in it.
        if (standard > 0 && took > 0 && took <= max(BREAK_MS, standard * 4)) {
            actualMs += took
            standardMs += standard
        }
        mark(along, timeMs, standardLeftMs, true)
    }

    private fun mark(along: Double, timeMs: Long, standardLeftMs: Long, onRoute: Boolean) {
        markAlong = along.takeIf { onRoute }
        markTime = timeMs
        markStandardLeft = standardLeftMs
    }

    private companion object {
        /** Progress shorter than this is GPS wander. */
        const val STEP_M = 20.0
        const val BREAK_MS = 3 * 60_000L

        /** After this much standard time walked, the pace is all from this walk. */
        const val FULL_WEIGHT_MS = 45 * 60_000L
    }
}
