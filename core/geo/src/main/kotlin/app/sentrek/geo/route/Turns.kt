package app.sentrek.geo.route

import app.sentrek.geo.Geo
import app.sentrek.geo.LatLon
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot

/**
 * Where a route turns at a junction of the path network: a path of the map data leaves the
 * route there, and either the route itself turns sharply or that other path keeps straighter
 * than the way the route goes. A bend with no other path, or a side path the route passes by,
 * is not a turn. Only as good as the map data: no lines, no turns.
 */
object Turns {
    /** Metres along the route of each turn, in order. */
    fun find(route: Route, lines: List<List<LatLon>>): List<Double> {
        if (route.length < 2 * APPROACH_M) return emptyList()
        val local = Local(route.points.first())
        val path = route.points.map(local::xy)
        val along = DoubleArray(path.size)
        for (i in 1 until path.size) along[i] = along[i - 1] + dist(path[i - 1], path[i])
        val index = SegmentIndex(path)

        // Each place where a line leaves the route, either way along it: how far along the
        // route, and the way the line goes from there. In steps, since lines cross the route
        // between their vertices, and a shallow fork only parts from it gradually.
        val branches = mutableListOf<Pair<Double, Double>>()
        for (line in lines) {
            val xy = densify(line.map(local::xy))
            val on = xy.map { p -> index.nearest(p, along)?.takeIf { it.second <= ON_ROUTE_M } }
            val reach = (BRANCH_M / STEP_M).toInt()
            for (i in 1 until xy.size) {
                val leave = when {
                    on[i - 1] != null && on[i] == null -> Triple(i - 1, i - 1 + reach, on[i - 1]!!)
                    on[i] != null && on[i - 1] == null -> Triple(i, i - reach, on[i]!!)
                    else -> continue
                }
                val (from, to, at) = leave
                if (to !in xy.indices) continue
                val off = index.nearest(xy[to], along)?.second ?: Double.MAX_VALUE
                if (off > OFF_ROUTE_M) branches += at.first to heading(xy[from], xy[to])
            }
        }

        val turns = mutableListOf<Double>()
        for (junction in cluster(branches)) {
            val d = junction.first
            if (d < APPROACH_M || d > route.length - APPROACH_M) continue
            // Clear of the junction both ways: a fork's parting point lies a little past it.
            val inward = heading(pointAt(path, along, d - LEG_M - GAP_M), pointAt(path, along, d - GAP_M))
            val outward = heading(pointAt(path, along, d + GAP_M / 3), pointAt(path, along, d + GAP_M / 3 + LEG_M))
            val routeTurn = angle(inward, outward)
            val straighter = junction.second.any { angle(inward, it) < routeTurn - STRAIGHTER_DEG }
            if (routeTurn >= SHARP_DEG || (routeTurn >= MIN_TURN_DEG && straighter)) {
                if (turns.lastOrNull()?.let { d - it < MERGE_M } != true) turns += d
            }
        }
        return turns
    }

    /** Branches within [MERGE_M] of each other along the route: one junction, at their mean. */
    private fun cluster(branches: List<Pair<Double, Double>>): List<Pair<Double, List<Double>>> {
        val out = mutableListOf<Pair<Double, List<Double>>>()
        var group = mutableListOf<Pair<Double, Double>>()
        for (b in branches.sortedBy { it.first }) {
            if (group.isNotEmpty() && b.first - group.last().first > MERGE_M) {
                out += group.map { it.first }.average() to group.map { it.second }
                group = mutableListOf()
            }
            group += b
        }
        if (group.isNotEmpty()) out += group.map { it.first }.average() to group.map { it.second }
        return out
    }

    /** [line] with a point every [STEP_M] metres or so. */
    private fun densify(line: List<DoubleArray>): List<DoubleArray> {
        val out = mutableListOf(line.first())
        for (i in 1 until line.size) {
            val a = line[i - 1]
            val b = line[i]
            val n = (dist(a, b) / STEP_M).toInt().coerceAtLeast(1)
            for (k in 1..n) out += doubleArrayOf(a[0] + (b[0] - a[0]) * k / n, a[1] + (b[1] - a[1]) * k / n)
        }
        return out
    }

    private fun pointAt(path: List<DoubleArray>, along: DoubleArray, d: Double): DoubleArray {
        val target = d.coerceIn(0.0, along.last())
        var i = along.indexOfFirst { it >= target }.coerceAtLeast(1)
        if (i >= path.size) i = path.lastIndex
        val span = along[i] - along[i - 1]
        val t = if (span == 0.0) 0.0 else (target - along[i - 1]) / span
        val a = path[i - 1]
        val b = path[i]
        return doubleArrayOf(a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t)
    }

    private fun heading(a: DoubleArray, b: DoubleArray) = Math.toDegrees(atan2(b[0] - a[0], b[1] - a[1]))

    /** The smaller angle between two headings, 0–180°. */
    private fun angle(a: Double, b: Double): Double {
        val d = abs(a - b) % 360.0
        return if (d > 180) 360 - d else d
    }

    private fun dist(a: DoubleArray, b: DoubleArray) = hypot(b[0] - a[0], b[1] - a[1])

    /** Metres east and north of an origin: flat enough for a route. */
    private class Local(origin: LatLon) {
        private val lat0 = origin.lat
        private val lon0 = origin.lon
        private val ky = Geo.EARTH_RADIUS_M * Math.toRadians(1.0)
        private val kx = ky * cos(Math.toRadians(lat0))
        fun xy(p: LatLon) = doubleArrayOf((p.lon - lon0) * kx, (p.lat - lat0) * ky)
    }

    /** The route's segments in a grid, for the nearest point on it to many points. */
    private class SegmentIndex(private val path: List<DoubleArray>) {
        private val cells = HashMap<Long, MutableList<Int>>()

        init {
            for (i in 0 until path.size - 1) {
                val a = path[i]
                val b = path[i + 1]
                for (cx in cell(minOf(a[0], b[0]))..cell(maxOf(a[0], b[0]))) {
                    for (cy in cell(minOf(a[1], b[1]))..cell(maxOf(a[1], b[1]))) {
                        cells.getOrPut(key(cx, cy)) { mutableListOf() } += i
                    }
                }
            }
        }

        /** (metres along, metres off) of the nearest point on the route within a cell or so; null if none. */
        fun nearest(p: DoubleArray, along: DoubleArray): Pair<Double, Double>? {
            var best: Pair<Double, Double>? = null
            val cx = cell(p[0])
            val cy = cell(p[1])
            for (x in cx - 1..cx + 1) for (y in cy - 1..cy + 1) {
                for (i in cells[key(x, y)] ?: continue) {
                    val a = path[i]
                    val b = path[i + 1]
                    val dx = b[0] - a[0]
                    val dy = b[1] - a[1]
                    val len2 = dx * dx + dy * dy
                    val t = if (len2 == 0.0) 0.0 else (((p[0] - a[0]) * dx + (p[1] - a[1]) * dy) / len2).coerceIn(0.0, 1.0)
                    val off = hypot(a[0] + t * dx - p[0], a[1] + t * dy - p[1])
                    if (best == null || off < best.second) best = (along[i] + t * (along[i + 1] - along[i])) to off
                }
            }
            return best
        }

        private fun cell(v: Double) = floor(v / CELL_M).toInt()
        private fun key(x: Int, y: Int) = (x.toLong() shl 32) or (y.toLong() and 0xffffffffL)
    }

    /** A line vertex this close to the route is on it (GPS-recorded routes wander a little). */
    private const val ON_ROUTE_M = 12.0

    /** ...and a line this far from it [BRANCH_M] after parting has left it. */
    private const val OFF_ROUTE_M = 20.0
    private const val BRANCH_M = 30.0

    /** The route's direction before and after the junction: over [LEG_M], [GAP_M] away from it. */
    private const val LEG_M = 40.0
    private const val GAP_M = 30.0

    private const val STEP_M = 5.0

    private const val SHARP_DEG = 45.0
    private const val MIN_TURN_DEG = 20.0
    private const val STRAIGHTER_DEG = 20.0

    /** Branches this close along the route are one junction; turns this close are one turn. */
    private const val MERGE_M = 30.0

    /** No turn this close to either end: there's nothing to warn about yet, or any more. */
    const val APPROACH_M = 50.0

    private const val CELL_M = 50.0
}
