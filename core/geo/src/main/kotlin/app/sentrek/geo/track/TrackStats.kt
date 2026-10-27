package app.sentrek.geo.track

import app.sentrek.geo.Geo
import app.sentrek.geo.LatLon
import kotlin.math.abs

/** One fix of a recorded or imported track. [time] in epoch milliseconds, 0 when unknown. */
data class TrackPoint(val position: LatLon, val time: Long)

/**
 * Distance, climb and duration of a track, updated point by point while recording.
 *
 * GPS altitude jitters by ±10 m, so climb is counted with hysteresis: elevation changes only
 * count once they exceed [CLIMB_THRESHOLD_M] from the last reference, which filters the noise
 * without losing real climbs.
 */
class TrackStats {
    var distance = 0.0
        private set
    var ascent = 0.0
        private set
    var descent = 0.0
        private set
    var startTime = 0L
        private set
    var endTime = 0L
        private set
    var pointCount = 0
        private set

    /** Time within segments, so pauses (gaps between segments) don't count. */
    var activeMillis = 0L
        private set

    private var last: TrackPoint? = null
    private var eleRef: Double? = null

    /** [newSegment]: don't join to the previous point (after a pause or an app restart). */
    fun add(point: TrackPoint, newSegment: Boolean = false) {
        val previous = last
        if (previous != null && !newSegment) {
            distance += Geo.distance(previous.position, point.position)
            if (previous.time > 0 && point.time > previous.time) activeMillis += point.time - previous.time
        }
        point.position.ele?.let { ele ->
            val ref = eleRef
            when {
                ref == null -> eleRef = ele
                abs(ele - ref) >= CLIMB_THRESHOLD_M -> {
                    if (ele > ref) ascent += ele - ref else descent += ref - ele
                    eleRef = ele
                }
            }
        }
        if (point.time > 0) {
            if (startTime == 0L) startTime = point.time
            endTime = point.time
        }
        pointCount++
        last = point
    }

    companion object {
        const val CLIMB_THRESHOLD_M = 8.0

        fun of(segments: List<List<TrackPoint>>): TrackStats = TrackStats().apply {
            for (segment in segments) segment.forEachIndexed { i, p -> add(p, newSegment = i == 0) }
        }
    }
}
