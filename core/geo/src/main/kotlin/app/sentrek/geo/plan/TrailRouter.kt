package app.sentrek.geo.plan

import app.sentrek.geo.Geo
import app.sentrek.geo.LatLon
import java.util.PriorityQueue
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.roundToLong

/** A line of the map's path network. [cost] multiplies its length: 1 for a footpath, more for roads. */
class TrailLine(val points: List<LatLon>, val cost: Double = 1.0)

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

    private class Edge(val to: Int, val cost: Double)

    private class Segment(val a: Int, val b: Int, val cost: Double)

    private val segments = ArrayList<Segment>()
    private val grid = HashMap<Long, MutableList<Int>>()

    init {
        for (line in lines) {
            var previous = -1
            for (p in line.points) {
                val node = node((p.lon - lon0) * kx, (p.lat - lat0) * ky)
                if (previous >= 0 && previous != node) link(previous, node, line.cost)
                previous = node
            }
        }
        indexSegments()
        joinVerticesOnLines()
        indexSegments()
    }

    /** A point on the network: on segment [segment], at [t] (0 = its a end, 1 = its b end). */
    private class Snap(val segment: Segment, val t: Double, val x: Double, val y: Double, val distance: Double)

    /** Where the network passes nearest to [p], if within [maxM]. */
    fun snap(p: LatLon, maxM: Double): LatLon? = snap(x(p), y(p), maxM)?.let { position(it.x, it.y) }

    /**
     * The cheapest way along the network from [from] to [to], each first moved to the nearest
     * line within [maxSnapM]. Null when either is too far from a line, or they aren't connected.
     */
    fun route(from: LatLon, to: LatLon, maxSnapM: Double): List<LatLon>? {
        val start = snap(x(from), y(from), maxSnapM) ?: return null
        val end = snap(x(to), y(to), maxSnapM) ?: return null

        // Two extra nodes where the leg meets the network, linked to their segment's ends.
        val s = xs.size
        val e = s + 1
        val extra = HashMap<Int, MutableList<Edge>>()
        fun extraLink(from: Int, to: Int, cost: Double) {
            extra.getOrPut(from) { mutableListOf() } += Edge(to, cost)
            extra.getOrPut(to) { mutableListOf() } += Edge(from, cost)
        }
        fun attach(node: Int, snap: Snap) {
            val seg = snap.segment
            val length = dist(seg.a, seg.b)
            extraLink(node, seg.a, length * snap.t * seg.cost)
            extraLink(node, seg.b, length * (1 - snap.t) * seg.cost)
        }
        attach(s, start)
        attach(e, end)
        // Both on one segment: straight along it is one option (another way may still cost less).
        if (start.segment === end.segment) {
            extraLink(s, e, dist(start.segment.a, start.segment.b) * abs(start.t - end.t) * start.segment.cost)
        }
        val nx = { n: Int -> if (n == s) start.x else if (n == e) end.x else xs[n] }
        val ny = { n: Int -> if (n == s) start.y else if (n == e) end.y else ys[n] }

        // A*: the straight distance never overestimates, as no line costs less than 1 per metre.
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
                val next = cost + edge.cost
                if (next < (best[edge.to] ?: Double.MAX_VALUE)) {
                    best[edge.to] = next
                    previous[edge.to] = n
                    queue += (next + hypot(nx(edge.to) - end.x, ny(edge.to) - end.y)) to edge.to
                }
            }
        }
        if (e !in best) return null
        val path = ArrayList<LatLon>()
        var n = e
        while (true) {
            path += position(nx(n), ny(n))
            n = previous[n] ?: break
        }
        return path.reversed()
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

    private fun link(a: Int, b: Int, cost: Double) {
        if (edges[a].any { it.to == b }) return
        val w = dist(a, b) * cost
        edges[a] += Edge(b, w)
        edges[b] += Edge(a, w)
        segments += Segment(a, b, cost)
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
                if (n != from) link(from, n, seg.cost)
                from = n
            }
            if (from != seg.b) link(from, seg.b, seg.cost)
        }
    }

    private fun snap(x: Double, y: Double, maxM: Double): Snap? {
        var best: Snap? = null
        for (i in segmentsNear(x, y, maxM)) {
            val seg = segments[i]
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
    }
}
