package app.sentrek.map

import app.sentrek.geo.LatLon
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Where the direction arrows go: on each track, the stretches no track drawn above it covers.
 * Where two tracks share a trail, only the top line's arrows show; two sets pointing different
 * ways along the same line can't be told apart.
 */
internal object ArrowParts {
    /** Two recordings of the same trail are rarely further apart than this. */
    const val SHARED_M = 25.0

    /** Each line is checked every so often along its length. */
    private const val STEP_M = 10.0

    /** A free stretch shorter than this would only hold half an arrow between two shared ones. */
    private const val MIN_FREE_M = 40.0

    private const val M_PER_DEG = 111_320.0

    /**
     * [tracks]: each one's segments, bottom first (the order they're drawn). Returns, for
     * each track, the lines its arrows go on.
     */
    fun free(tracks: List<List<List<LatLon>>>, sharedM: Double = SHARED_M): List<List<List<LatLon>>> {
        val first = tracks.firstNotNullOfOrNull { t -> t.firstNotNullOfOrNull { it.firstOrNull() } }
            ?: return tracks.map { emptyList() }
        val kx = M_PER_DEG * cos(Math.toRadians(first.lat))
        val above = Grid(sharedM)
        val out = ArrayList<List<List<LatLon>>>(tracks.size)
        // From the top down: each track is checked against the ones above it, then joins them.
        for (track in tracks.asReversed()) {
            val lines = track.filter { it.size >= 2 }.map { s -> s.map { Xy(it.lon * kx, it.lat * M_PER_DEG) } }
            out += lines.flatMap { line -> freeParts(line, above, sharedM) }
                .map { part -> part.map { LatLon(it.y / M_PER_DEG, it.x / kx) } }
            lines.forEach { line -> line.zipWithNext().forEach { (a, b) -> above.add(a, b) } }
        }
        return out.asReversed()
    }

    /** The runs of [line] (sampled every [STEP_M]) further than [sharedM] from anything in [above]. */
    private fun freeParts(line: List<Xy>, above: Grid, sharedM: Double): List<List<Xy>> {
        if (above.isEmpty()) return listOf(line)
        val parts = ArrayList<List<Xy>>()
        var run = ArrayList<Xy>()
        var runM = 0.0
        var shared = false
        fun close() {
            if (run.size >= 2 && runM >= MIN_FREE_M) parts += run
            run = ArrayList()
            runM = 0.0
        }
        for ((a, b) in line.zipWithNext()) {
            val length = hypot(b.x - a.x, b.y - a.y)
            val steps = max(1, (length / STEP_M).roundToInt())
            val from = if (run.isEmpty()) 0 else 1
            for (i in from..steps) {
                val t = i.toDouble() / steps
                val p = Xy(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)
                if (above.near(p, sharedM)) {
                    shared = true
                    close()
                } else {
                    run.lastOrNull()?.let { runM += hypot(p.x - it.x, p.y - it.y) }
                    run += p
                }
            }
        }
        close()
        // Nothing above it here: the line as it is, not the samples along it.
        return if (shared) parts else listOf(line)
    }

    private data class Xy(val x: Double, val y: Double)

    /** Segments by square cell, so a point is checked only against those nearby. */
    private class Grid(private val cell: Double) {
        private val cells = HashMap<Long, MutableList<Pair<Xy, Xy>>>()

        fun isEmpty() = cells.isEmpty()

        /** In pieces no longer than a cell: a long straight leg would cover a square of cells. */
        fun add(a: Xy, b: Xy) {
            val pieces = max(1, kotlin.math.ceil(hypot(b.x - a.x, b.y - a.y) / cell).toInt())
            var from = a
            for (i in 1..pieces) {
                val t = i.toDouble() / pieces
                val to = Xy(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)
                addPiece(from, to)
                from = to
            }
        }

        private fun addPiece(a: Xy, b: Xy) {
            for (cx in index(min(a.x, b.x))..index(max(a.x, b.x))) {
                for (cy in index(min(a.y, b.y))..index(max(a.y, b.y))) {
                    cells.getOrPut(key(cx, cy)) { ArrayList() } += a to b
                }
            }
        }

        fun near(p: Xy, distance: Double): Boolean {
            val cx = index(p.x)
            val cy = index(p.y)
            for (x in cx - 1..cx + 1) for (y in cy - 1..cy + 1) {
                cells[key(x, y)]?.forEach { (a, b) -> if (distance(p, a, b) <= distance) return true }
            }
            return false
        }

        private fun index(v: Double) = floor(v / cell).toInt()

        private fun key(x: Int, y: Int) = (x.toLong() shl 32) or (y.toLong() and 0xFFFFFFFFL)

        private fun distance(p: Xy, a: Xy, b: Xy): Double {
            val dx = b.x - a.x
            val dy = b.y - a.y
            val l2 = dx * dx + dy * dy
            val t = if (l2 == 0.0) 0.0 else (((p.x - a.x) * dx + (p.y - a.y) * dy) / l2).coerceIn(0.0, 1.0)
            val ex = a.x + dx * t - p.x
            val ey = a.y + dy * t - p.y
            return sqrt(ex * ex + ey * ey)
        }
    }
}
