package app.sentrek.geo

import kotlin.math.cos
import kotlin.math.sqrt

object Simplify {
    private const val M_PER_DEG = Math.PI / 180.0 * Geo.EARTH_RADIUS_M

    /**
     * Douglas–Peucker simplification of a polyline given as parallel [lons]/[lats] arrays.
     * Distances use a local equirectangular projection, accurate at trail scale.
     * Returns the indices of the points to keep, always including first and last.
     */
    fun douglasPeucker(lons: DoubleArray, lats: DoubleArray, toleranceM: Double): IntArray {
        val n = lons.size
        require(lats.size == n)
        if (n <= 2) return IntArray(n) { it }

        val kx = cos(Math.toRadians(lats[0])) * M_PER_DEG
        val keep = BooleanArray(n).apply { this[0] = true; this[n - 1] = true }
        val stack = ArrayDeque<Long>().apply { addLast(pack(0, n - 1)) }

        while (stack.isNotEmpty()) {
            val range = stack.removeLast()
            val first = (range ushr 32).toInt()
            val last = range.toInt()
            var maxDist = 0.0
            var maxIdx = -1
            for (i in first + 1 until last) {
                val d = segmentDistance(lons, lats, i, first, last, kx)
                if (d > maxDist) { maxDist = d; maxIdx = i }
            }
            if (maxIdx >= 0 && maxDist > toleranceM) {
                keep[maxIdx] = true
                stack.addLast(pack(first, maxIdx))
                stack.addLast(pack(maxIdx, last))
            }
        }
        return keep.indices.filter { keep[it] }.toIntArray()
    }

    private fun pack(a: Int, b: Int): Long = (a.toLong() shl 32) or (b.toLong() and 0xFFFFFFFFL)

    private fun segmentDistance(lons: DoubleArray, lats: DoubleArray, p: Int, a: Int, b: Int, kx: Double): Double {
        val ax = lons[a] * kx; val ay = lats[a] * M_PER_DEG
        val bx = lons[b] * kx; val by = lats[b] * M_PER_DEG
        val px = lons[p] * kx; val py = lats[p] * M_PER_DEG
        val dx = bx - ax; val dy = by - ay
        val len2 = dx * dx + dy * dy
        val t = if (len2 == 0.0) 0.0 else (((px - ax) * dx + (py - ay) * dy) / len2).coerceIn(0.0, 1.0)
        val ex = px - (ax + t * dx); val ey = py - (ay + t * dy)
        return sqrt(ex * ex + ey * ey)
    }
}
