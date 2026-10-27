package app.sentrek.geo.plan

import app.sentrek.geo.Geo
import app.sentrek.geo.LatLon
import kotlin.math.ceil

/**
 * The points of a route being planned, with undo and redo. Every change is one step in the
 * history; a drag is applied once, when the finger lifts.
 */
class PlanEditor<T> {
    private val history = mutableListOf<List<T>>(emptyList())
    private var current = 0

    val points: List<T> get() = history[current]
    val canUndo: Boolean get() = current > 0
    val canRedo: Boolean get() = current < history.size - 1

    fun add(p: T) = push(points + p)

    /** Inserts [p] so it becomes point [index]. */
    fun insert(index: Int, p: T) = push(points.toMutableList().apply { add(index.coerceIn(0, size), p) })

    fun move(index: Int, p: T) {
        if (index !in points.indices) return
        push(points.toMutableList().apply { set(index, p) })
    }

    fun remove(index: Int) {
        if (index !in points.indices) return
        push(points.toMutableList().apply { removeAt(index) })
    }

    fun clear() {
        if (points.isNotEmpty()) push(emptyList())
    }

    fun undo() {
        if (canUndo) current--
    }

    fun redo() {
        if (canRedo) current++
    }

    private fun push(next: List<T>) {
        // A new change after some undos drops the undone steps.
        while (history.size > current + 1) history.removeAt(history.size - 1)
        history += next
        current++
    }
}

/** A leg as drawn: along the paths, or straight ([onTrails] false, also when no way was found). */
data class PlanLeg(val points: List<LatLon>, val onTrails: Boolean) {
    /** Where the handle that inserts a point on this leg goes. */
    val middle: LatLon get() = PlanGeometry.halfway(points)
}

object PlanGeometry {
    /**
     * The straight legs between [points], with a point at least every [stepM] metres: enough to
     * sample the terrain along each leg. The given points are all kept.
     */
    fun densify(points: List<LatLon>, stepM: Double = 30.0): List<LatLon> {
        if (points.size < 2) return points
        val out = ArrayList<LatLon>(points.size * 4)
        out += points.first()
        for (i in 1 until points.size) {
            val a = points[i - 1]
            val b = points[i]
            val steps = ceil(Geo.distance(a, b) / stepM).toInt().coerceAtLeast(1)
            for (s in 1 until steps) {
                val t = s.toDouble() / steps
                out += LatLon(a.lat + (b.lat - a.lat) * t, a.lon + (b.lon - a.lon) * t)
            }
            out += b
        }
        return out
    }

    /** Middle of each leg: where the planner draws the handles that insert a point. */
    fun midpoints(points: List<LatLon>): List<LatLon> =
        points.zipWithNext { a, b -> LatLon((a.lat + b.lat) / 2, (a.lon + b.lon) / 2) }

    /** The point halfway along a polyline, by distance. */
    fun halfway(line: List<LatLon>): LatLon {
        if (line.size < 2) return line.first()
        var left = Geo.length(line) / 2
        for (i in 1 until line.size) {
            val a = line[i - 1]
            val b = line[i]
            val d = Geo.distance(a, b)
            if (d >= left && d > 0) {
                val t = left / d
                return LatLon(a.lat + (b.lat - a.lat) * t, a.lon + (b.lon - a.lon) * t)
            }
            left -= d
        }
        return line.last()
    }
}
