package app.sentrek.geo.dem

import app.sentrek.geo.Geo
import app.sentrek.geo.LatLon
import kotlin.math.atan2
import kotlin.math.max

/**
 * What the eye sees from a place: the skyline all around, from the terrain heights, and which
 * named peaks stand on it or in front of it rather than hidden behind a nearer ridge. The
 * earth's curve (less a little for the bending of light) lowers what's far away.
 */
class Panorama(private val tiles: TerrainTiles) {
    /** A peak in view: compass [azimuth] and [elevation] angle in degrees, [distance] in metres. */
    data class Peak(val name: String, val position: LatLon, val height: Double, val azimuth: Double, val elevation: Double, val distance: Double)

    /** The skyline: the highest angle in degrees for each [STEP_DEG] of azimuth, from north. */
    class Skyline(val angles: FloatArray) {
        fun at(azimuth: Double): Float {
            val i = (((azimuth % 360 + 360) % 360) / STEP_DEG).toInt() % angles.size
            return angles[i]
        }
    }

    private val cache = HashMap<Terrarium.TileKey, Terrarium.Tile?>()

    /** The terrain height at [p], or null where there's no tile for it. */
    fun height(p: LatLon): Double? {
        val sample = Terrarium.locate(p.lat, p.lon)
        val tile = cache.getOrPut(sample.key) { tiles.tile(sample.key) } ?: return null
        return Terrarium.height(tile, sample).toDouble()
    }

    /** The skyline from [from], the eye [eyeHeight] metres above its ground (or [from]'s own height). */
    fun skyline(from: LatLon, eyeHeight: Double, maxDistance: Double = MAX_DISTANCE_M): Skyline {
        val eye = (from.ele ?: height(from) ?: 0.0) + eyeHeight
        val angles = FloatArray((360 / STEP_DEG).toInt()) { i -> maxAngle(from, eye, i * STEP_DEG, maxDistance).toFloat() }
        return Skyline(angles)
    }

    /**
     * Of [candidates] (names and places), those within [maxDistance] that the eye at [from]
     * can see: none of the ground in between rises above the line to them.
     */
    fun peaks(from: LatLon, eyeHeight: Double, candidates: List<Pair<String, LatLon>>, maxDistance: Double = MAX_DISTANCE_M): List<Peak> {
        val eye = (from.ele ?: height(from) ?: 0.0) + eyeHeight
        return candidates.mapNotNull { (name, p) ->
            val distance = Geo.distance(from, p)
            if (distance < MIN_PEAK_M || distance > maxDistance) return@mapNotNull null
            val top = p.ele ?: height(p) ?: return@mapNotNull null
            val azimuth = Geo.bearing(from, p)
            val angle = angle(top - eye, distance)
            val blocking = maxAngle(from, eye, azimuth, distance - minOf(CLEAR_M, distance * CLEAR_SHARE))
            if (angle + TOLERANCE_DEG < blocking) return@mapNotNull null
            Peak(name, p, top, azimuth, angle, distance)
        }.sortedBy { it.azimuth }
    }

    /** The highest ground angle along [azimuth] out to [maxDistance]. */
    private fun maxAngle(from: LatLon, eye: Double, azimuth: Double, maxDistance: Double): Double {
        var best = -90.0
        var d = FIRST_M
        while (d <= maxDistance) {
            val h = height(Geo.destination(from, azimuth, d))
            if (h != null) best = max(best, angle(h - eye, d))
            d += max(MIN_STEP_M, d * STEP_GROWTH)
        }
        return best
    }

    private companion object {
        const val STEP_DEG = 0.5
        const val MAX_DISTANCE_M = 30_000.0
        const val FIRST_M = 60.0
        const val MIN_STEP_M = 30.0
        const val STEP_GROWTH = 0.012

        /** Peaks closer than this are where the user stands, more or less. */
        const val MIN_PEAK_M = 300.0

        /** The ground just under a summit is the summit: not something hiding it. */
        const val CLEAR_M = 250.0
        const val CLEAR_SHARE = 0.15

        /** Terrain heights are smoothed at this scale: a summit comes out a little low. */
        const val TOLERANCE_DEG = 0.3

        /** Light bends a little around the earth: 13 % of the drop, as surveyors take it. */
        const val REFRACTION = 0.13

        /** Degrees above the eye, with the earth's curve: the drop is d²/2R. */
        fun angle(rise: Double, distance: Double): Double {
            val drop = distance * distance / (2 * Geo.EARTH_RADIUS_M) * (1 - REFRACTION)
            return Math.toDegrees(atan2(rise - drop, distance))
        }
    }
}
