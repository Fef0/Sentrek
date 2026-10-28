package app.sentrek.geo.dem

import app.sentrek.geo.units.ElevationUnit
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Contour lines from a grid of heights (marching squares), drawn on the phone from the same
 * terrain tiles as the hillshade, so they work wherever the hillshade does, offline included.
 */
object Contours {
    /** A contour at [level] (in the grid's units) through grid points x0, y0, x1, y1, … */
    class Line(val level: Int, val xy: DoubleArray) {
        val closed: Boolean get() = xy.size >= 6 && xy[0] == xy[xy.size - 2] && xy[1] == xy[xy.size - 1]
    }

    /** A line every [interval]; every [index] a thicker, labelled one. */
    data class Spacing(val interval: Int, val index: Int)

    /**
     * Sparser further out, like paper maps: in metres 100/50/20/10 (Swiss 1:25k style at
     * close zoom), in feet 400/200/80/40 (USGS 1:24k uses 40 ft).
     */
    fun spacing(zoom: Int, unit: ElevationUnit): Spacing = when (unit) {
        ElevationUnit.Meters -> when {
            zoom <= 12 -> Spacing(100, 500)
            zoom == 13 -> Spacing(50, 250)
            zoom == 14 -> Spacing(20, 100)
            else -> Spacing(10, 50)
        }
        ElevationUnit.Feet -> when {
            zoom <= 12 -> Spacing(400, 2000)
            zoom == 13 -> Spacing(200, 1000)
            zoom == 14 -> Spacing(80, 400)
            else -> Spacing(40, 200)
        }
    }

    /**
     * Lines at every positive multiple of [interval] through a [width] × [height] grid, row by
     * row. Sea level and below are left out: on the coast they would only trace the shore.
     */
    fun trace(heights: FloatArray, width: Int, height: Int, interval: Int): List<Line> {
        require(heights.size == width * height && interval > 0)
        val segments = HashMap<Int, Segments>()
        for (j in 0 until height - 1) {
            for (i in 0 until width - 1) {
                val tl = heights[j * width + i]
                val tr = heights[j * width + i + 1]
                val bl = heights[(j + 1) * width + i]
                val br = heights[(j + 1) * width + i + 1]
                val min = minOf(minOf(tl, tr), minOf(bl, br))
                val max = maxOf(maxOf(tl, tr), maxOf(bl, br))
                val first = maxOf(ceil(min / interval).toInt(), 1)
                val last = floor(max / interval).toInt()
                for (k in first..last) {
                    val level = (k * interval).toFloat()
                    val case = (if (tl >= level) 8 else 0) or (if (tr >= level) 4 else 0) or
                        (if (br >= level) 2 else 0) or (if (bl >= level) 1 else 0)
                    if (case == 0 || case == 15) continue
                    val top = hEdge(i, j, width)
                    val bottom = hEdge(i, j + 1, width)
                    val left = vEdge(i, j, width)
                    val right = vEdge(i + 1, j, width)
                    val s = segments.getOrPut(k) { Segments() }
                    when (case) {
                        1, 14 -> s.add(left, bottom)
                        2, 13 -> s.add(bottom, right)
                        3, 12 -> s.add(left, right)
                        4, 11 -> s.add(top, right)
                        6, 9 -> s.add(top, bottom)
                        7, 8 -> s.add(left, top)
                        // Saddles: the centre decides which corners the line cuts off.
                        5, 10 -> {
                            val centreAbove = (tl + tr + bl + br) / 4 >= level
                            if ((case == 5) == centreAbove) {
                                s.add(left, top); s.add(bottom, right)
                            } else {
                                s.add(top, right); s.add(left, bottom)
                            }
                        }
                    }
                }
            }
        }
        return segments.flatMap { (k, s) ->
            val level = (k * interval).toFloat()
            s.join().map { edges ->
                val xy = DoubleArray(edges.size * 2)
                edges.forEachIndexed { n, edge -> point(edge, level, heights, width, xy, n * 2) }
                Line(k * interval, xy)
            }
        }
    }

    // Edge ids: the horizontal edge right of grid point (i, j) is even, the vertical one below it odd.
    private fun hEdge(i: Int, j: Int, width: Int) = (j * width + i) * 2
    private fun vEdge(i: Int, j: Int, width: Int) = (j * width + i) * 2 + 1

    /** Where [level] crosses [edge], always interpolated in the same direction so neighbours agree. */
    private fun point(edge: Int, level: Float, heights: FloatArray, width: Int, out: DoubleArray, at: Int) {
        val p = edge / 2
        val i = p % width
        val j = p / width
        val a = heights[p]
        val b = if (edge % 2 == 0) heights[p + 1] else heights[p + width]
        val t = if (b == a) 0.5 else ((level - a) / (b - a)).toDouble().coerceIn(0.0, 1.0)
        out[at] = if (edge % 2 == 0) i + t else i.toDouble()
        out[at + 1] = if (edge % 2 == 0) j.toDouble() else j + t
    }

    /** One level's segments (pairs of edges), joined into lines. */
    private class Segments {
        private var a = IntArray(16)
        private var b = IntArray(16)
        private var size = 0

        fun add(from: Int, to: Int) {
            if (size == a.size) {
                a = a.copyOf(size * 2); b = b.copyOf(size * 2)
            }
            a[size] = from; b[size] = to; size++
        }

        fun join(): List<IntArray> {
            // An edge is shared by at most two cells, so by at most two segments.
            val byEdge = HashMap<Int, IntArray>(size * 2)
            for (s in 0 until size) {
                for (edge in intArrayOf(a[s], b[s])) {
                    val slots = byEdge.getOrPut(edge) { intArrayOf(-1, -1) }
                    if (slots[0] < 0) slots[0] = s else slots[1] = s
                }
            }
            val used = BooleanArray(size)
            val lines = mutableListOf<IntArray>()
            fun walk(startEdge: Int, startSegment: Int) {
                val edges = ArrayList<Int>()
                edges += startEdge
                var edge = startEdge
                var s = startSegment
                while (s >= 0 && !used[s]) {
                    used[s] = true
                    edge = if (a[s] == edge) b[s] else a[s]
                    edges += edge
                    val slots = byEdge.getValue(edge)
                    s = if (slots[0] == s) slots[1] else slots[0]
                }
                if (edges.size >= 2) lines += edges.toIntArray()
            }
            // Open lines start where only one segment touches the edge (the grid border).
            for ((edge, slots) in byEdge) {
                if (slots[1] < 0 && !used[slots[0]]) walk(edge, slots[0])
            }
            // The rest are rings.
            for (s in 0 until size) if (!used[s]) walk(a[s], s)
            return lines
        }
    }

    /**
     * Rounds the corners (Chaikin, [passes] times): the terrain grid is coarse, and straight
     * cell-to-cell steps look jagged up close. Ends of open lines stay put.
     */
    fun smooth(line: Line, passes: Int = 2): Line {
        var xy = line.xy
        val closed = line.closed
        repeat(passes) {
            val n = xy.size / 2
            if (n < 3) return Line(line.level, xy)
            val out = ArrayList<Double>(n * 4)
            if (!closed) { out += xy[0]; out += xy[1] }
            for (p in 0 until n - 1) {
                val x0 = xy[p * 2]; val y0 = xy[p * 2 + 1]
                val x1 = xy[p * 2 + 2]; val y1 = xy[p * 2 + 3]
                out += 0.75 * x0 + 0.25 * x1; out += 0.75 * y0 + 0.25 * y1
                out += 0.25 * x0 + 0.75 * x1; out += 0.25 * y0 + 0.75 * y1
            }
            if (closed) { out += out[0]; out += out[1] } else { out += xy[xy.size - 2]; out += xy[xy.size - 1] }
            xy = out.toDoubleArray()
        }
        return Line(line.level, xy)
    }

    /** A 3 × 3 box blur, so single noisy pixels don't become tiny rings. Edges are kept. */
    fun blur(heights: FloatArray, width: Int, height: Int): FloatArray {
        val out = heights.copyOf()
        for (j in 1 until height - 1) {
            for (i in 1 until width - 1) {
                var sum = 0f
                for (dj in -1..1) for (di in -1..1) sum += heights[(j + dj) * width + i + di]
                out[j * width + i] = sum / 9
            }
        }
        return out
    }
}
