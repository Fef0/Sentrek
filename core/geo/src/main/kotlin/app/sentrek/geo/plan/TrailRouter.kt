package app.sentrek.geo.plan

import app.sentrek.geo.Geo
import app.sentrek.geo.LatLon
import java.util.PriorityQueue
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.roundToLong

/**
 * A line of the map's path network. [cost] multiplies its length: 1 for a footpath or a quiet
 * road, a little more for a busy one. [grade]: its SAC grade 1–6 when the map data has one;
 * [ferrata]: a via ferrata; [road]: a road for cars (not a track).
 */
class TrailLine(
    val points: List<LatLon>,
    val cost: Double = 1.0,
    val grade: Int? = null,
    val ferrata: Boolean = false,
    val road: Boolean = false,
)

/**
 * How the planner picks its way along the paths, kept with each route. [avoided]: spots the
 * route must not pass (a closed path, a bridge that's gone).
 */
data class RouteOptions(
    val profile: Profile = Profile.Shortest,
    val avoidFerratas: Boolean = false,
    /** Paths and tracks only: no roads for cars. */
    val avoidRoads: Boolean = false,
    val avoided: List<LatLon> = emptyList(),
) {
    enum class Profile {
        /** The shortest way, along paths and roads alike. */
        Shortest,

        /** Lower SAC grades and gentler slopes, even if longer. */
        Easiest,

        /** As little climbing as it takes. */
        LeastClimb,
    }

    /** Whether the costs need the terrain's heights. */
    val needsHeights: Boolean get() = profile != Profile.Shortest
}

/**
 * The path network from map tile lines, for routing a planned leg along trails. Tile data
 * has no topology: lines meet where they share a vertex, and a vertex lying on another line
 * (within [JOIN_M]) is joined to it too, since simplification can drop the shared vertex.
 */
class TrailGraph(lines: List<TrailLine>) {
    private val lat0 = lines.firstOrNull()?.points?.firstOrNull()?.lat ?: 0.0
    private val lon0 = lines.firstOrNull()?.points?.firstOrNull()?.lon ?: 0.0
    private val ky = Geo.EARTH_RADIUS_M * Math.toRadians(1.0)
    private val kx = ky * cos(Math.toRadians(lat0))

    private val xs = ArrayList<Double>()
    private val ys = ArrayList<Double>()
    private val edges = ArrayList<MutableList<Edge>>()
    private val nodeAt = HashMap<Long, Int>()

    /** What a stretch of path is: how much it costs depends on the [RouteOptions]. */
    private class Kind(val cost: Double, val grade: Int?, val ferrata: Boolean, val road: Boolean)

    private class Edge(val to: Int, val length: Double, val kind: Kind)

    private class Segment(val a: Int, val b: Int, val kind: Kind) {
        val key: Long get() = edgeKey(a, b)
    }

    private val segments = ArrayList<Segment>()
    private val grid = HashMap<Long, MutableList<Int>>()

    init {
        for (line in lines) {
            val kind = Kind(line.cost, line.grade, line.ferrata, line.road)
            var previous = -1
            for (p in line.points) {
                val node = node((p.lon - lon0) * kx, (p.lat - lat0) * ky)
                if (previous >= 0 && previous != node) link(previous, node, kind)
                previous = node
            }
        }
        indexSegments()
        joinVerticesOnLines()
        indexSegments()
    }

    /** The network's points, to look up their heights for [route]. */
    val nodes: List<LatLon> get() = xs.indices.map { position(xs[it], ys[it]) }

    /** A point on the network: on segment [segment], at [t] (0 = its a end, 1 = its b end). */
    private class Snap(val segment: Segment, val t: Double, val x: Double, val y: Double, val distance: Double)

    /** Where the network passes nearest to [p], if within [maxM]. */
    fun snap(p: LatLon, maxM: Double): LatLon? = snap(x(p), y(p), maxM)?.let { position(it.x, it.y) }

    /**
     * The cheapest way along the network from [from] to [to], each first moved to the nearest
     * line within [maxSnapM]. Null when either is too far from a line, or they aren't connected.
     * [heights]: the height of each of [nodes] (NaN where unknown), for the profiles that
     * count climbing.
     */
    fun route(
        from: LatLon,
        to: LatLon,
        maxSnapM: Double,
        options: RouteOptions = RouteOptions(),
        heights: DoubleArray? = null,
    ): List<LatLon>? = search(from, to, maxSnapM, options, heights, emptySet())?.first

    /**
     * Up to [count] different ways from [from] to [to], the best first. Each next one is found
     * with the paths already used made dearer, and kept only if it's mostly elsewhere and not
     * much longer than the best.
     */
    fun alternatives(
        from: LatLon,
        to: LatLon,
        maxSnapM: Double,
        options: RouteOptions = RouteOptions(),
        heights: DoubleArray? = null,
        count: Int = 3,
    ): List<List<LatLon>> {
        val best = search(from, to, maxSnapM, options, heights, emptySet()) ?: return emptyList()
        val found = arrayListOf(best.first)
        val used = HashSet(best.second)
        val bestLength = Geo.length(best.first)
        // A few tries: some come back the same way, or far too long.
        repeat(count * 2) {
            if (found.size >= count) return found
            val next = search(from, to, maxSnapM, options, heights, used) ?: return found
            val shared = next.second.count { it in used }.toDouble() / next.second.size.coerceAtLeast(1)
            used += next.second
            if (shared < MAX_SHARED && Geo.length(next.first) <= bestLength * MAX_DETOUR) found += next.first
        }
        return found
    }

    /** The path and the edges it uses (as [edgeKey]s), or null. [dearer]: edges that count [ALTERNATIVE_PENALTY] times. */
    private fun search(
        from: LatLon,
        to: LatLon,
        maxSnapM: Double,
        options: RouteOptions,
        heights: DoubleArray?,
        dearer: Set<Long>,
    ): Pair<List<LatLon>, List<Long>>? {
        val blocked = blockedSegments(options.avoided)
        // Onto a path that may be used: a closed one or a via ferrata to avoid is no start.
        val usable = { seg: Segment -> seg.key !in blocked && allowed(seg.kind, options) }
        val start = snap(x(from), y(from), maxSnapM, usable) ?: return null
        val end = snap(x(to), y(to), maxSnapM, usable) ?: return null

        // Two extra nodes where the leg meets the network, linked to their segment's ends.
        val s = xs.size
        val e = s + 1
        val extra = HashMap<Int, MutableList<Edge>>()
        fun extraLink(from: Int, to: Int, length: Double, kind: Kind) {
            extra.getOrPut(from) { mutableListOf() } += Edge(to, length, kind)
            extra.getOrPut(to) { mutableListOf() } += Edge(from, length, kind)
        }
        fun attach(node: Int, snap: Snap) {
            val seg = snap.segment
            val length = dist(seg.a, seg.b)
            extraLink(node, seg.a, length * snap.t, seg.kind)
            extraLink(node, seg.b, length * (1 - snap.t), seg.kind)
        }
        attach(s, start)
        attach(e, end)
        // Both on one segment: straight along it is one option (another way may still cost less).
        if (start.segment === end.segment) {
            extraLink(s, e, dist(start.segment.a, start.segment.b) * abs(start.t - end.t), start.segment.kind)
        }
        val nx = { n: Int -> if (n == s) start.x else if (n == e) end.x else xs[n] }
        val ny = { n: Int -> if (n == s) start.y else if (n == e) end.y else ys[n] }
        // The ends' heights: along their segment, between its ends' heights.
        fun between(snap: Snap): Double {
            val a = heights?.getOrNull(snap.segment.a) ?: Double.NaN
            val b = heights?.getOrNull(snap.segment.b) ?: Double.NaN
            return a + (b - a) * snap.t
        }
        val startHeight = between(start)
        val endHeight = between(end)
        fun height(n: Int): Double = when (n) {
            s -> startHeight
            e -> endHeight
            else -> heights?.getOrNull(n) ?: Double.NaN
        }

        // A*: the straight distance never overestimates, as no edge costs less than its length.
        val best = HashMap<Int, Double>()
        val previous = HashMap<Int, Int>()
        val queue = PriorityQueue<Pair<Double, Int>>(compareBy { it.first })
        best[s] = 0.0
        queue += 0.0 to s
        while (queue.isNotEmpty()) {
            val (_, n) = queue.poll()
            if (n == e) break
            val cost = best.getValue(n)
            val out = (if (n < xs.size) edges[n] else emptyList<Edge>()) + extra[n].orEmpty()
            for (edge in out) {
                val network = n < xs.size && edge.to < xs.size
                if (network && edgeKey(n, edge.to) in blocked) continue
                val step = cost(edge, height(n), height(edge.to), options) ?: continue
                val dear = if (network && edgeKey(n, edge.to) in dearer) ALTERNATIVE_PENALTY else 1.0
                val next = cost + step * dear
                if (next < (best[edge.to] ?: Double.MAX_VALUE)) {
                    best[edge.to] = next
                    previous[edge.to] = n
                    queue += (next + hypot(nx(edge.to) - end.x, ny(edge.to) - end.y)) to edge.to
                }
            }
        }
        if (e !in best) return null
        val path = ArrayList<LatLon>()
        val used = ArrayList<Long>()
        var n = e
        while (true) {
            path += position(nx(n), ny(n))
            val p = previous[n] ?: break
            if (p < xs.size && n < xs.size) used += edgeKey(p, n)
            n = p
        }
        return path.reversed() to used
    }

    /**
     * What walking [edge] costs, going from [fromHeight] to [toHeight]; null when it may not be
     * used. Never less than its length, so A*'s straight-line guess stays below the truth.
     */
    private fun cost(edge: Edge, fromHeight: Double, toHeight: Double, options: RouteOptions): Double? {
        val kind = edge.kind
        if (!allowed(kind, options)) return null
        var cost = edge.length * kind.cost
        val climb = (toHeight - fromHeight).takeIf { !it.isNaN() } ?: 0.0
        when (options.profile) {
            RouteOptions.Profile.Shortest -> Unit
            RouteOptions.Profile.Easiest -> {
                cost *= GRADE_FACTOR[(kind.grade ?: 1).coerceIn(1, 6)] * if (kind.ferrata) EASIEST_FERRATA else 1.0
                cost += climb.coerceAtLeast(0.0) * EASIEST_CLIMB
                // Steep stretches, up or down, are harder going than their height says.
                if (edge.length > 0 && abs(climb) / edge.length > STEEP) cost += edge.length * STEEP_EXTRA
            }
            RouteOptions.Profile.LeastClimb -> cost += climb.coerceAtLeast(0.0) * LEAST_CLIMB
        }
        return cost
    }

    private fun allowed(kind: Kind, options: RouteOptions) =
        !(kind.ferrata && options.avoidFerratas) && !(kind.road && options.avoidRoads)

    /** The segments passing within [AVOID_M] of an avoided spot, as [edgeKey]s. */
    private fun blockedSegments(avoided: List<LatLon>): Set<Long> {
        if (avoided.isEmpty()) return emptySet()
        val blocked = HashSet<Long>()
        for (spot in avoided) {
            val x = x(spot)
            val y = y(spot)
            for (i in segmentsNear(x, y, AVOID_M)) {
                val seg = segments[i]
                if (project(x, y, seg).second <= AVOID_M) blocked += seg.key
            }
        }
        return blocked
    }

    private fun node(x: Double, y: Double): Int {
        val key = ((x / QUANTUM_M).roundToLong() shl 32) xor ((y / QUANTUM_M).roundToLong() and 0xFFFFFFFFL)
        return nodeAt.getOrPut(key) {
            xs += x
            ys += y
            edges.add(mutableListOf())
            xs.size - 1
        }
    }

    private fun link(a: Int, b: Int, kind: Kind) {
        if (edges[a].any { it.to == b }) return
        val length = dist(a, b)
        edges[a] += Edge(b, length, kind)
        edges[b] += Edge(a, length, kind)
        segments += Segment(a, b, kind)
    }

    private fun unlink(a: Int, b: Int) {
        edges[a].removeAll { it.to == b }
        edges[b].removeAll { it.to == a }
    }

    private fun indexSegments() {
        grid.clear()
        segments.forEachIndexed { i, seg ->
            val x0 = minOf(xs[seg.a], xs[seg.b])
            val x1 = maxOf(xs[seg.a], xs[seg.b])
            val y0 = minOf(ys[seg.a], ys[seg.b])
            val y1 = maxOf(ys[seg.a], ys[seg.b])
            for (cx in cell(x0)..cell(x1)) for (cy in cell(y0)..cell(y1)) grid.getOrPut(cellKey(cx, cy)) { mutableListOf() } += i
        }
    }

    /** Splits segments that pass within [JOIN_M] of another line's vertex, through that vertex. */
    private fun joinVerticesOnLines() {
        val splits = HashMap<Int, MutableList<Pair<Double, Int>>>()
        for (n in xs.indices) {
            for (i in segmentsNear(xs[n], ys[n], JOIN_M)) {
                val seg = segments[i]
                if (seg.a == n || seg.b == n) continue
                val (t, d) = project(xs[n], ys[n], seg)
                if (d <= JOIN_M && t > 0.0 && t < 1.0) splits.getOrPut(i) { mutableListOf() } += t to n
            }
        }
        if (splits.isEmpty()) return
        val kept = segments.filterIndexed { i, _ -> i !in splits }
        val split = splits.map { (i, at) -> segments[i] to at.sortedBy { it.first } }
        segments.clear()
        segments += kept
        for ((seg, at) in split) {
            unlink(seg.a, seg.b)
            var from = seg.a
            for ((_, n) in at) {
                if (n != from) link(from, n, seg.kind)
                from = n
            }
            if (from != seg.b) link(from, seg.b, seg.kind)
        }
    }

    private fun snap(x: Double, y: Double, maxM: Double, usable: (Segment) -> Boolean = { true }): Snap? {
        var best: Snap? = null
        for (i in segmentsNear(x, y, maxM)) {
            val seg = segments[i]
            if (!usable(seg)) continue
            val (t, d) = project(x, y, seg)
            if (d <= maxM && (best == null || d < best.distance)) {
                best = Snap(seg, t, xs[seg.a] + (xs[seg.b] - xs[seg.a]) * t, ys[seg.a] + (ys[seg.b] - ys[seg.a]) * t, d)
            }
        }
        return best
    }

    /** Position along [seg] nearest to (x, y), clamped to it, and the distance to it. */
    private fun project(x: Double, y: Double, seg: Segment): Pair<Double, Double> {
        val ax = xs[seg.a]
        val ay = ys[seg.a]
        val dx = xs[seg.b] - ax
        val dy = ys[seg.b] - ay
        val len2 = dx * dx + dy * dy
        val t = if (len2 == 0.0) 0.0 else (((x - ax) * dx + (y - ay) * dy) / len2).coerceIn(0.0, 1.0)
        return t to hypot(ax + dx * t - x, ay + dy * t - y)
    }

    private fun segmentsNear(x: Double, y: Double, r: Double): Set<Int> {
        val found = HashSet<Int>()
        for (cx in cell(x - r)..cell(x + r)) for (cy in cell(y - r)..cell(y + r)) grid[cellKey(cx, cy)]?.let { found += it }
        return found
    }

    private fun dist(a: Int, b: Int) = hypot(xs[b] - xs[a], ys[b] - ys[a])
    private fun x(p: LatLon) = (p.lon - lon0) * kx
    private fun y(p: LatLon) = (p.lat - lat0) * ky
    private fun position(x: Double, y: Double) = LatLon(y / ky + lat0, x / kx + lon0)
    private fun cell(v: Double) = floor(v / CELL_M).toInt()
    private fun cellKey(cx: Int, cy: Int) = (cx.toLong() shl 32) xor (cy.toLong() and 0xFFFFFFFFL)

    companion object {
        /** Vertices closer than this are the same node: tile coordinates are exact to ~0.5 m. */
        const val QUANTUM_M = 0.5
        const val JOIN_M = 2.0
        private const val CELL_M = 25.0

        /** How near an avoided spot a path is closed. */
        const val AVOID_M = 25.0

        /** Easiest: each SAC grade's cost per metre (index 1–6), and a via ferrata's on top. */
        private val GRADE_FACTOR = doubleArrayOf(1.0, 1.0, 1.0, 1.5, 3.0, 6.0, 10.0)
        private const val EASIEST_FERRATA = 4.0

        /** Metres of walking that a metre of climb counts as: about what it costs in time (DIN 33466). */
        private const val EASIEST_CLIMB = 8.0
        private const val LEAST_CLIMB = 30.0

        /** Steeper than this (rise over run), a stretch costs [STEEP_EXTRA] more per metre. */
        private const val STEEP = 0.3
        private const val STEEP_EXTRA = 2.0

        /** How much dearer the paths of the ways found already are, when looking for another. */
        private const val ALTERNATIVE_PENALTY = 1.8

        /** Another way is shown if it's at most this much longer than the best... */
        private const val MAX_DETOUR = 1.6

        /** ...and shares less than this part of its stretches with the ways found before. */
        private const val MAX_SHARED = 0.7

        /** One key for the segment between two nodes, whichever way round. */
        private fun edgeKey(a: Int, b: Int): Long = (minOf(a, b).toLong() shl 32) or maxOf(a, b).toLong()
    }
}
