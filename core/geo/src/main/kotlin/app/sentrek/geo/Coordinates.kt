package app.sentrek.geo

import kotlin.math.asin
import kotlin.math.atan2
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

    /** Initial compass direction from [a] to [b], degrees clockwise from north (0–360). */
    fun bearing(a: LatLon, b: LatLon): Double {
        val phi1 = Math.toRadians(a.lat)
        val phi2 = Math.toRadians(b.lat)
        val dLon = Math.toRadians(b.lon - a.lon)
        val y = sin(dLon) * cos(phi2)
        val x = cos(phi1) * sin(phi2) - sin(phi1) * cos(phi2) * cos(dLon)
        return (Math.toDegrees(atan2(y, x)) + 360) % 360
    }

    /** The point [distance] metres from [from] towards compass [bearing] (degrees), on the sphere. */
    fun destination(from: LatLon, bearing: Double, distance: Double): LatLon {
        val d = distance / EARTH_RADIUS_M
        val b = Math.toRadians(bearing)
        val phi1 = Math.toRadians(from.lat)
        val phi2 = asin(sin(phi1) * cos(d) + cos(phi1) * sin(d) * cos(b))
        val lambda = Math.toRadians(from.lon) + atan2(sin(b) * sin(d) * cos(phi1), cos(d) - sin(phi1) * sin(phi2))
        return LatLon(Math.toDegrees(phi2), (Math.toDegrees(lambda) + 540) % 360 - 180)
    }

    /** Total length of a polyline in metres. */
    fun length(points: List<LatLon>): Double =
        points.zipWithNext().sumOf { (a, b) -> distance(a, b) }
}
