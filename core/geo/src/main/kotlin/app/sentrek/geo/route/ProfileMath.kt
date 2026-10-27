package app.sentrek.geo.route

import kotlin.math.atan
import kotlin.math.roundToInt

/** Reading an elevation profile (metres along to metres high, by increasing distance). */
object ProfileMath {
    /** Half the stretch a slope is measured over: wide enough to smooth out GPS noise. */
    const val SLOPE_HALF_WINDOW_M = 50.0

    /** Height at [d] metres along, between the profile's points (the ends past them). */
    fun heightAt(profile: List<Pair<Double, Double>>, d: Double): Double {
        val i = profile.indexOfFirst { it.first >= d }
        if (i == 0) return profile.first().second
        if (i < 0) return profile.last().second
        val (d0, e0) = profile[i - 1]
        val (d1, e1) = profile[i]
        return if (d1 == d0) e1 else e0 + (e1 - e0) * (d - d0) / (d1 - d0)
    }

    /**
     * Slope at [d] in degrees, positive uphill in the profile's direction, measured over
     * [halfWindowM] either side (less at the ends). 0 on a profile too short to tell.
     */
    fun slopeDegrees(profile: List<Pair<Double, Double>>, d: Double, halfWindowM: Double = SLOPE_HALF_WINDOW_M): Double {
        val from = (d - halfWindowM).coerceAtLeast(profile.first().first)
        val to = (d + halfWindowM).coerceAtMost(profile.last().first)
        if (to - from < 1.0) return 0.0
        val rise = heightAt(profile, to) - heightAt(profile, from)
        return Math.toDegrees(atan(rise / (to - from)))
    }

    /** "↗ 12°", "↘ 8°" or "→ 0°": direction and steepness at a glance. */
    fun slopeLabel(degrees: Double): String {
        val rounded = degrees.roundToInt()
        return when {
            rounded > 0 -> "↗ $rounded°"
            rounded < 0 -> "↘ ${-rounded}°"
            else -> "→ 0°"
        }
    }
}
