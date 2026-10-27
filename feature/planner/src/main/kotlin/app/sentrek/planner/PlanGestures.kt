package app.sentrek.planner

import android.graphics.PointF
import android.view.MotionEvent
import app.sentrek.geo.LatLon
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import kotlin.math.hypot

/** A handle of the plan under the finger: a point, or the middle of leg [index]. */
sealed interface PlanHandle {
    data class Point(val index: Int) : PlanHandle
    data class Mid(val index: Int) : PlanHandle
}

/**
 * Touches on the planner's handles, seen before the map: a touch that starts on a handle is
 * a tap or a drag of that handle and never pans the map. Any other touch goes to the map as
 * usual (where a tap adds a point). [hitPx]: how far from a handle's centre a touch counts.
 */
class PlanGestures(
    private val hitPx: Float,
    private val slopPx: Float,
    private val onTap: (PlanHandle) -> Unit,
    /** The handle is being dragged to [LatLon]: preview only. */
    private val onDrag: (PlanHandle, LatLon) -> Unit,
    /** Finger lifted after a drag: apply it. Null position: the drag was cancelled. */
    private val onDrop: (PlanHandle, LatLon?) -> Unit,
) {
    var map: MapLibreMap? = null
    var points: List<LatLon> = emptyList()

    /** The handles in the middle of each leg. */
    var midpoints: List<LatLon> = emptyList()

    private var handle: PlanHandle? = null
    private var downX = 0f
    private var downY = 0f
    private var dragging = false

    fun onTouch(event: MotionEvent): Boolean {
        val m = map ?: return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                handle = hit(m, event.x, event.y)
                downX = event.x
                downY = event.y
                dragging = false
                return handle != null
            }
            MotionEvent.ACTION_MOVE -> {
                val h = handle ?: return false
                if (!dragging && hypot(event.x - downX, event.y - downY) > slopPx) dragging = true
                if (dragging) onDrag(h, at(m, event.x, event.y))
                return true
            }
            MotionEvent.ACTION_UP -> {
                val h = handle ?: return false
                handle = null
                if (dragging) onDrop(h, at(m, event.x, event.y)) else onTap(h)
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                val h = handle ?: return false
                handle = null
                if (dragging) onDrop(h, null)
                return true
            }
        }
        // Second finger etc.: ours while a handle is held.
        return handle != null
    }

    /** The nearest handle within reach; points win over leg middles. */
    private fun hit(m: MapLibreMap, x: Float, y: Float): PlanHandle? {
        fun nearest(candidates: List<LatLon>): Int? = candidates.indices
            .map { i -> i to m.projection.toScreenLocation(LatLng(candidates[i].lat, candidates[i].lon)) }
            .map { (i, s) -> i to hypot(s.x - x, s.y - y) }
            .filter { (_, d) -> d <= hitPx }
            .minByOrNull { (_, d) -> d }
            ?.first
        nearest(points)?.let { return PlanHandle.Point(it) }
        nearest(midpoints)?.let { return PlanHandle.Mid(it) }
        return null
    }

    private fun at(m: MapLibreMap, x: Float, y: Float): LatLon {
        val p = m.projection.fromScreenLocation(PointF(x, y)).wrap()
        return LatLon(p.latitude.coerceIn(-85.0, 85.0), p.longitude)
    }
}
