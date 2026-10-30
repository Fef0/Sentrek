package app.sentrek.geo.route

import app.sentrek.geo.LatLon
import app.sentrek.geo.Sun
import app.sentrek.geo.dem.Panorama

/**
 * Sun and shade on the way ahead: for points along the route, whether the sun is above the
 * terrain around them at the time the user gets there. On a hot day the long sunny climb is
 * the one to start early; on a cold one, the shaded stretch is where the ice stays.
 */
object Shade {
    /** A point of the way: [ahead] metres on, reached at [eta], at [position]. */
    data class Point(val ahead: Double, val eta: Long, val position: LatLon)

    /** A stretch all in sun or all in shade, from [from] to [to] metres on, entered at [start]. */
    data class Stretch(val sun: Boolean, val from: Double, val to: Double, val start: Long, val end: Long) {
        val millis: Long get() = end - start
    }

    /**
     * The way ahead as stretches of sun and shade. Night counts as shade: the sun is down.
     * [points] in walking order; the terrain from [panorama].
     */
    fun stretches(points: List<Point>, panorama: Panorama): List<Stretch> {
        val stretches = mutableListOf<Stretch>()
        for (p in points) {
            val sun = Sun.position(p.eta, p.position.lat, p.position.lon)
            val lit = sun.altitude > 0 && sun.altitude > panorama.horizon(p.position, EYE_M, sun.azimuth, SHADOW_DISTANCE_M)
            val last = stretches.lastOrNull()
            if (last != null && last.sun == lit) {
                stretches[stretches.size - 1] = last.copy(to = p.ahead, end = p.eta)
            } else {
                // The change happens somewhere between the two points: the middle will do.
                if (last != null) stretches[stretches.size - 1] = last.copy(to = p.ahead, end = p.eta)
                stretches += Stretch(lit, p.ahead, p.ahead, p.eta, p.eta)
            }
        }
        return stretches.filter { it.to > it.from || stretches.size == 1 }
    }

    /**
     * What's worth a word: the next long stretch in the sun ahead, or the current one if the
     * user is in it. Short ones (a gap in the trees) and far ones (later than [horizonMs]) say nothing.
     */
    fun nextSunny(stretches: List<Stretch>, now: Long, horizonMs: Long = HORIZON_MS): Stretch? =
        stretches.firstOrNull { it.sun && it.millis >= LONG_MS && it.start - now <= horizonMs }

    /** The ground, not the eye: the sun on the path is what counts, a person is a bit above it. */
    private const val EYE_M = 2.0

    /** Shadows reach this far: beyond, the sun is lower than anything worth walking in. */
    const val SHADOW_DISTANCE_M = 15_000.0

    /** A stretch shorter than this is a clearing, not a slope in the sun. */
    const val LONG_MS = 30 * 60_000L

    /** How far in time is worth telling. */
    const val HORIZON_MS = 4 * 3_600_000L
}
