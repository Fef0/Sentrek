package app.sentrek.geo.route

import app.sentrek.geo.Geo
import app.sentrek.geo.LatLon
import app.sentrek.geo.track.TrackStats
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** Where the user is relative to a route. */
data class RoutePosition(
    /** Metres along the route to the nearest point on it. */
    val along: Double,
    /** Metres from the route. */
    val offset: Double,
)

/**
 * A route to follow: a polyline with distances and climb precomputed, so each GPS fix costs
 * a look at the stretch around the last position.
 */
class Route(val points: List<LatLon>) {
    init {
        require(points.size >= 2) { "A route needs at least two points" }
    }

    /** Distance from the start to each point. */
    private val cumulative = DoubleArray(points.size)

    /** Ascent and descent from the start to each point. */
    private val ascentTo = DoubleArray(points.size)
    private val descentTo = DoubleArray(points.size)

    val length: Double get() = cumulative.last()
    val ascent: Double get() = ascentTo.last()
    val descent: Double get() = descentTo.last()
    val hasElevation: Boolean = points.any { it.ele != null }

    init {
        var ref: Double? = null
        var up = 0.0
        var down = 0.0
        for (i in points.indices) {
            if (i > 0) cumulative[i] = cumulative[i - 1] + Geo.distance(points[i - 1], points[i])
            // Planned routes have smooth DEM elevations: a small hysteresis is enough.
            points[i].ele?.let { ele ->
                val r = ref
                if (r == null) {
                    ref = ele
                } else if (abs(ele - r) >= CLIMB_THRESHOLD_M) {
                    if (ele > r) up += ele - r else down += r - ele
                    ref = ele
                }
            }
            ascentTo[i] = up
            descentTo[i] = down
        }
    }

    fun reversed(): Route = Route(points.reversed())

    /**
     * Distance from the start to height, at most [maxPoints] of them (evenly picked): what an
     * elevation profile of the route needs. Empty without heights.
     */
    fun profile(maxPoints: Int = 400): List<Pair<Double, Double>> {
        val all = points.indices.mapNotNull { i -> points[i].ele?.let { cumulative[i] to it } }
        if (all.size <= maxPoints) return all
        val step = all.size.toDouble() / maxPoints
        return List(maxPoints) { all[(it * step).toInt()] } + all.last()
    }

    /**
     * Nearest point on the route. Where the route passes near itself (out-and-back, loops), the
     * candidate closest to [previousAlong] wins, so the position doesn't jump to the other leg.
     * With [previousAlong], only the stretch within [WINDOW_M] of it is searched (a fix every
     * second on a long route), unless the user is far from that stretch: a shortcut, a new
     * start.
     */
    fun locate(p: LatLon, previousAlong: Double? = null): RoutePosition {
        if (previousAlong != null) {
            val from = indexAt(previousAlong - WINDOW_M)
            val to = min(indexAt(previousAlong + WINDOW_M) + 1, points.size - 1)
            val near = nearest(p, previousAlong, from, to)
            if (near.offset <= FAR_FROM_WINDOW_M) return near
        }
        return nearest(p, previousAlong, 0, points.size - 1)
    }

    /**
     * Nearest point on segments [from] until [to]; of those about as near ([SAME_PLACE_M]), the
     * one closest to [previousAlong], or without it the first along the route: where the route
     * passes twice (a loop's start and end, an out-and-back), the walk is just beginning rather
     * than over. Two passes and no lists: this runs for every fix.
     */
    private fun nearest(p: LatLon, previousAlong: Double?, from: Int, to: Int): RoutePosition {
        // Local flat projection around p: accurate to well under a metre over a few km.
        val kx = Geo.EARTH_RADIUS_M * Math.toRadians(1.0) * cos(Math.toRadians(p.lat))
        val ky = Geo.EARTH_RADIUS_M * Math.toRadians(1.0)
        var best = Double.MAX_VALUE
        for (i in from until to) best = min(best, offset(i, p, kx, ky, at(i, p, kx, ky)))
        var along = 0.0
        var offset = 0.0
        var score = Double.MAX_VALUE
        for (i in from until to) {
            val t = at(i, p, kx, ky)
            val o = offset(i, p, kx, ky, t)
            if (o > best + SAME_PLACE_M) continue
            val a = cumulative[i] + t * (cumulative[i + 1] - cumulative[i])
            val s = if (previousAlong == null) a else abs(a - previousAlong)
            if (s < score) {
                score = s
                along = a
                offset = o
            }
        }
        return RoutePosition(along, offset)
    }

    /** Where along segment [i] (0 to 1) is nearest to [p], in the projection of [nearest]. */
    private fun at(i: Int, p: LatLon, kx: Double, ky: Double): Double {
        val a = points[i]
        val b = points[i + 1]
        val ax = (a.lon - p.lon) * kx
        val ay = (a.lat - p.lat) * ky
        val dx = (b.lon - p.lon) * kx - ax
        val dy = (b.lat - p.lat) * ky - ay
        val len2 = dx * dx + dy * dy
        return if (len2 == 0.0) 0.0 else ((-ax * dx - ay * dy) / len2).coerceIn(0.0, 1.0)
    }

    /** Metres from [p] to the point [t] along segment [i]. */
    private fun offset(i: Int, p: LatLon, kx: Double, ky: Double, t: Double): Double {
        val a = points[i]
        val b = points[i + 1]
        val ax = (a.lon - p.lon) * kx
        val ay = (a.lat - p.lat) * ky
        return hypot(ax + t * ((b.lon - p.lon) * kx - ax), ay + t * ((b.lat - p.lat) * ky - ay))
    }

    /** Distance, climb and walking time left from [along] to the end. */
    fun remaining(along: Double): Remaining {
        val i = indexAt(along)
        val distance = max(0.0, length - along)
        // Interpolate within the segment, or the climb left jumps by a whole segment at a time.
        val j = min(i + 1, points.size - 1)
        val span = cumulative[j] - cumulative[i]
        val f = if (span > 0) ((along - cumulative[i]) / span).coerceIn(0.0, 1.0) else 0.0
        val up = ascent - (ascentTo[i] + f * (ascentTo[j] - ascentTo[i]))
        val down = descent - (descentTo[i] + f * (descentTo[j] - descentTo[i]))
        return Remaining(distance, up, down, HikingTime.millis(distance, up, down))
    }

    /** The route's point at or just before [along] metres from the start. */
    fun pointAt(along: Double): LatLon = points[indexAt(along)]

    /** Last point at or before [along]. */
    private fun indexAt(along: Double): Int {
        var lo = 0
        var hi = cumulative.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (cumulative[mid] <= along) lo = mid else hi = mid - 1
        }
        return lo
    }

    data class Remaining(val distance: Double, val ascent: Double, val descent: Double, val millis: Long)

    private companion object {
        const val CLIMB_THRESHOLD_M = TrackStats.SMOOTH_CLIMB_THRESHOLD_M

        /** Candidates this much farther than the nearest one still count as "here". */
        const val SAME_PLACE_M = 25.0

        /** With a previous position, the route is searched this far before and after it. */
        const val WINDOW_M = 500.0

        /** Farther than this from the searched stretch, the whole route is searched. */
        const val FAR_FROM_WINDOW_M = 2 * OffRouteDetector.OFF_ROUTE_M
    }
}

/**
 * Walking time as on Alpine signposts (DIN 33466): 4 km/h on the flat, 300 m/h up, 500 m/h
 * down; the larger of the horizontal and vertical times plus half the smaller.
 */
object HikingTime {
    fun millis(distanceM: Double, ascentM: Double, descentM: Double): Long {
        val horizontal = distanceM / 4_000.0
        val vertical = ascentM / 300.0 + descentM / 500.0
        val hours = max(horizontal, vertical) + min(horizontal, vertical) / 2
        return (hours * 3_600_000).toLong()
    }
}

/**
 * Turns distances from the route into alerts: off route after [delayMs] beyond [offM], back
 * on route once within [backM]. The two thresholds stop GPS jitter from toggling the alert.
 */
class OffRouteDetector(
    private val offM: Double = OFF_ROUTE_M,
    private val backM: Double = 30.0,
    private val delayMs: Long = 20_000,
) {
    enum class Event { OffRoute, BackOnRoute }

    companion object {
        /** Farther than this from the route is off it. */
        const val OFF_ROUTE_M = 50.0
    }

    var isOff = false
        private set

    private var awaySince: Long? = null

    fun update(offset: Double, time: Long): Event? {
        if (isOff) {
            if (offset <= backM) {
                isOff = false
                awaySince = null
                return Event.BackOnRoute
            }
            return null
        }
        if (offset <= offM) {
            awaySince = null
            return null
        }
        val since = awaySince ?: time.also { awaySince = it }
        if (time - since >= delayMs) {
            isOff = true
            return Event.OffRoute
        }
        return null
    }
}
