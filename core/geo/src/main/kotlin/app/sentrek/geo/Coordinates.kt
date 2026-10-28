package app.sentrek.geo

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** WGS84 position in decimal degrees; [ele] in metres when known. */
data class LatLon(val lat: Double, val lon: Double, val ele: Double? = null) {
    init {
        require(lat in -90.0..90.0) { "lat out of range: $lat" }
        require(lon in -180.0..180.0) { "lon out of range: $lon" }
    }
}

data class BoundingBox(val south: Double, val west: Double, val north: Double, val east: Double) {
    init {
        require(south <= north) { "south > north" }
        require(west <= east) { "west > east (antimeridian boxes not supported)" }
    }

    operator fun contains(p: LatLon): Boolean =
        p.lat in south..north && p.lon in west..east

    val center: LatLon get() = LatLon((south + north) / 2, (west + east) / 2)

    fun intersects(other: BoundingBox): Boolean =
        south <= other.north && other.south <= north && west <= other.east && other.west <= east
}

object Geo {
    const val EARTH_RADIUS_M = 6_371_008.8

    /** Great-circle distance in metres (haversine). */
    fun distance(a: LatLon, b: LatLon): Double {
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLon = Math.toRadians(b.lon - a.lon)
        val h = sin(dLat / 2).let { it * it } +
            cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLon / 2).let { it * it }
        return 2 * EARTH_RADIUS_M * asin(sqrt(h.coerceAtMost(1.0)))
    }

    /** Total length of a polyline in metres. */
    fun length(points: List<LatLon>): Double =
        points.zipWithNext().sumOf { (a, b) -> distance(a, b) }
}
