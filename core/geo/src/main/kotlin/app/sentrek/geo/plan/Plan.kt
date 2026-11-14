package app.sentrek.geo.plan

import app.sentrek.geo.Geo
import app.sentrek.geo.LatLon
import app.sentrek.geo.Simplify
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

    /** All the points at once, as one step (e.g. the route turned around). */
    fun replace(all: List<T>) {
        if (all != points) push(all)
    }

    /** Starts over from [start] (an existing plan to edit), with nothing to undo. */
    fun reset(start: List<T>) {
        history.clear()
        history += start
        current = 0
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

/**
 * A leg as drawn: along the paths, or straight ([onTrails] false, also when no way was found).
 * [offPathStart] / [offPathEnd]: the first / last stretch is a straight link between an
 * off-path point and the path.
 */
data class PlanLeg(
    val points: List<LatLon>,
    val onTrails: Boolean,
    val offPathStart: Boolean = false,
    val offPathEnd: Boolean = false,
) {
    /** Stretches to draw: along a path (true) or straight across country (false). */
    val pieces: List<Pair<List<LatLon>, Boolean>>
        get() {
            if (!onTrails || points.size < 2) return listOf(points to false)
            val from = if (offPathStart) 1 else 0
            val to = if (offPathEnd) points.lastIndex - 1 else points.lastIndex
            return listOfNotNull(
                if (offPathStart) points.subList(0, 2) to false else null,
                if (to > from) points.subList(from, to + 1) to true else null,
                if (offPathEnd) points.subList(points.size - 2, points.size) to false else null,
            )
        }

    /** Where the handle that inserts a point on this leg goes. */
    val middle: LatLon get() = PlanGeometry.halfway(points)

    /** The same leg walked the other way. */
    fun reversed(): PlanLeg = PlanLeg(points.reversed(), onTrails, offPathStart = offPathEnd, offPathEnd = offPathStart)
}

/**
 * A planned point. [onTrails]: the leg arriving here follows the paths on the map.
 * [sectionEnd]: a section of the route (a day, a stage) ends here and the next one starts.
 */
data class PlanPoint(val position: LatLon, val onTrails: Boolean, val sectionEnd: Boolean = false)

/** A section's totals; [millis] at the planned pace. Without terrain [hasElevation] is false. */
data class SectionTotals(
    val distance: Double,
    val ascent: Double,
    val descent: Double,
    val millis: Long,
    val hasElevation: Boolean,
    /** Height where the section starts and ends; null where the terrain isn't known. */
    val startEle: Double? = null,
    val endEle: Double? = null,
)

/** A route cut into sections at the points that end one. */
object Sections {
    /** The type of the waypoints a saved route has at its section ends (GPX "type"). */
    const val WAYPOINT_TYPE = "Sentrek section end"

    /**
     * The indices of the points ending a section, in order. The start can't end one, and the
     * end of the route ends the last section anyway: a mark there (put while planning, before
     * the next point) only counts once the route goes on.
     */
    fun ends(points: List<PlanPoint>): List<Int> = points.indices.filter { it in 1 until points.lastIndex && points[it].sectionEnd }

    /**
     * The legs of each section, as index ranges into the legs (leg i goes from point i to
     * i + 1). Empty when the route isn't cut: one section is no sections.
     */
    fun legRanges(points: List<PlanPoint>): List<IntRange> {
        val ends = ends(points)
        if (ends.isEmpty()) return emptyList()
        val bounds = listOf(0) + ends + points.lastIndex
        return bounds.zipWithNext { from, to -> from until to }
    }

    /**
     * Where [line] passes each of [ends], in order: the index of its nearest point after the
     * previous end's, so a route that comes back past a section end is cut where it reaches it
     * first. The ends of the line itself are not included.
     */
    fun cuts(line: List<LatLon>, ends: List<LatLon>): List<Int> {
        val cuts = mutableListOf<Int>()
        var from = 0
        for (end in ends) {
            if (from + 1 >= line.lastIndex) break
            val d = (from + 1 until line.lastIndex).map { Geo.distance(line[it], end) }
            val nearest = d.min()
            // The first pass within reach of the nearest, then down to its closest point: on the
            // way back the line may pass a few metres nearer, but the section ended the first time.
            var best = d.indexOfFirst { it <= nearest + PASS_M }
            while (best + 1 < d.size && d[best + 1] < d[best]) best++
            cuts += from + 1 + best
            from += 1 + best
        }
        return cuts
    }

    /** How much farther than the nearest point a first pass may be. */
    private const val PASS_M = 30.0

    /** [line] in pieces at [cuts], each sharing its first point with the end of the one before. */
    fun <T> pieces(line: List<T>, cuts: List<Int>): List<List<T>> {
        val bounds = listOf(0) + cuts + line.lastIndex
        return bounds.zipWithNext { from, to -> line.subList(from, to + 1) }
    }
}

/**
 * A plan as saved with its route, to edit it again: its points, the legs as drawn, and how
 * its legs pick their way ([options]).
 */
data class SavedPlan(val points: List<PlanPoint>, val legs: List<PlanLeg>, val options: RouteOptions = RouteOptions()) {
    /** The same route from its end to its start, every leg as it was. */
    fun reversed(): SavedPlan = SavedPlan(reversed(points), legs.reversed().map(PlanLeg::reversed), options)

    companion object {
        /**
         * [points] the other way. Each point says how the leg arriving at it is drawn: turned
         * around, that leg arrives at the point before it. The first point has no leg to arrive
         * by and keeps the old start's mode.
         */
        fun reversed(points: List<PlanPoint>): List<PlanPoint> {
            val n = points.lastIndex
            return points.indices.map { k ->
                val from = points[n - k]
                from.copy(onTrails = if (k == 0) points.first().onTrails else points[n - k + 1].onTrails)
            }
        }

        /**
         * A plan to edit any line (e.g. an imported route): points where it bends by more
         * than [toleranceM], and legs that keep the line's exact shape until they are changed.
         */
        fun fromLine(line: List<LatLon>, toleranceM: Double = 40.0): SavedPlan {
            val flat = line.map { it.copy(ele = null) }
            if (flat.size < 2) return SavedPlan(flat.map { PlanPoint(it, true) }, emptyList())
            val kept = Simplify.douglasPeucker(
                DoubleArray(flat.size) { flat[it].lon },
                DoubleArray(flat.size) { flat[it].lat },
                toleranceM,
            )
            return SavedPlan(
                points = kept.map { PlanPoint(flat[it], onTrails = true) },
                legs = kept.toList().zipWithNext { from, to -> PlanLeg(flat.subList(from, to + 1), onTrails = true) },
            )
        }
    }
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
