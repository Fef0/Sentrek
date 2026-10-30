package app.sentrek.geo.track

import app.sentrek.geo.Geo

/**
 * Keeps GPS jumps out of a recording: a fix that would need an impossible speed from the last
 * one kept (a bad fix under a cliff, after a tunnel) is dropped. If the fixes keep agreeing on
 * the new place, it was real after all (a train, a cable car, a phone switched on elsewhere):
 * it's taken, as the start of a new segment, so no straight line crosses the gap.
 */
class JumpFilter {
    enum class Verdict { Keep, Drop, NewSegment }

    private var lastKept: TrackPoint? = null
    private var elsewhere: TrackPoint? = null
    private var agreeing = 0

    /** What to do with [point]. Call [kept] for the points that do go into the recording. */
    fun check(point: TrackPoint): Verdict {
        val last = lastKept ?: return Verdict.Keep
        if (possible(last, point)) {
            elsewhere = null
            agreeing = 0
            return Verdict.Keep
        }
        val other = elsewhere
        agreeing = if (other != null && possible(other, point)) agreeing + 1 else 1
        elsewhere = point
        if (agreeing < AGREEING) return Verdict.Drop
        elsewhere = null
        agreeing = 0
        return Verdict.NewSegment
    }

    /** The point is in the recording now. */
    fun kept(point: TrackPoint) {
        lastKept = point
    }

    private fun possible(from: TrackPoint, to: TrackPoint): Boolean {
        val seconds = (to.time - from.time) / 1000.0
        val metres = Geo.distance(from.position, to.position)
        // Within the fix's own wander, whatever the time: a few seconds can't make 20 m impossible.
        return metres <= SLACK_M || (seconds > 0 && metres / seconds <= MAX_SPEED_MS)
    }

    private companion object {
        /** About 250 km/h: faster than anything with a hiker in it but a plane. */
        const val MAX_SPEED_MS = 70.0
        const val SLACK_M = 50.0

        /** Fixes in a row that agree on a new place before it's believed. */
        const val AGREEING = 3
    }
}
