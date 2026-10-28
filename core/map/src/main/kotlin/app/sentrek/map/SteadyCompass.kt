package app.sentrek.map

import android.os.SystemClock
import org.maplibre.android.location.CompassEngine
import org.maplibre.android.location.CompassListener
import kotlin.math.abs

/**
 * MapLibre's compass, passed on only when the heading really changes: the sensor reports ten
 * times a second, and every report turns the arrow and redraws the whole map, even with the
 * phone lying still. A few degrees of jitter now cost nothing.
 */
internal class SteadyCompass(private val source: CompassEngine) : CompassEngine, CompassListener {
    /** The fewest milliseconds between two turns of the arrow (longer in battery saver). */
    var minIntervalMs: Long = NORMAL_INTERVAL_MS

    private val listeners = mutableListOf<CompassListener>()
    private var heading = source.lastHeading
    private var sentAt = 0L

    override fun addCompassListener(listener: CompassListener) {
        if (listeners.isEmpty()) source.addCompassListener(this)
        listeners += listener
    }

    override fun removeCompassListener(listener: CompassListener) {
        listeners -= listener
        // The last listener gone (map covered or stopped): the sensor goes off too.
        if (listeners.isEmpty()) source.removeCompassListener(this)
    }

    override fun getLastHeading(): Float = heading

    override fun getLastAccuracySensorStatus(): Int = source.lastAccuracySensorStatus

    override fun onCompassChanged(userHeading: Float) {
        val now = SystemClock.elapsedRealtime()
        val turn = abs(((userHeading - heading) % 360 + 540) % 360 - 180)
        if (turn < MIN_TURN_DEGREES || now - sentAt < minIntervalMs) return
        heading = userHeading
        sentAt = now
        listeners.toList().forEach { it.onCompassChanged(userHeading) }
    }

    override fun onCompassAccuracyChange(compassStatus: Int) {
        listeners.toList().forEach { it.onCompassAccuracyChange(compassStatus) }
    }

    companion object {
        /** Less than this is sensor noise, or a hand that isn't quite still. */
        private const val MIN_TURN_DEGREES = 4f

        const val NORMAL_INTERVAL_MS = 200L
        const val SAVER_INTERVAL_MS = 1_000L
    }
}
