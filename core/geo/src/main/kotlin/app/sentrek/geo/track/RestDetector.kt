package app.sentrek.geo.track

import app.sentrek.geo.Geo
import app.sentrek.geo.LatLon

/**
 * Tells a long stop (a break, a hut, a night out) from walking: every fix within [radiusM] of
 * where the stop began, for [afterMs]. Standing still, the GPS wanders by some metres, more
 * under trees or rock walls: the radius allows for that. Fixes worse than [maxAccuracyM]
 * neither start nor end a stop.
 */
class RestDetector(
    private val radiusM: Double = 25.0,
    private val afterMs: Long = 3 * 60_000L,
    private val maxAccuracyM: Float = 30f,
) {
    private var anchor: LatLon? = null
    private var anchorTime = 0L

    var resting: Boolean = false
        private set

    /** A fix at [timeMs]; returns whether [resting] changed. */
    fun update(position: LatLon, timeMs: Long, accuracyM: Float?): Boolean {
        if (accuracyM != null && accuracyM > maxAccuracyM) return false
        val a = anchor
        if (a == null || Geo.distance(a, position) > radiusM) {
            anchor = position
            anchorTime = timeMs
            return setResting(false)
        }
        return if (timeMs - anchorTime >= afterMs) setResting(true) else false
    }

    /** Moving again by other means (the motion sensor): the stop is over. */
    fun moved(): Boolean {
        anchor = null
        return setResting(false)
    }

    private fun setResting(value: Boolean): Boolean {
        if (resting == value) return false
        resting = value
        return true
    }
}
