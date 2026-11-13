package app.sentrek.geo

import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/** UTM on WGS84 (Snyder's series), both ways, in a given zone. */
object Utm {
    /** Easting and northing (m) of [p] in [zone]; northing from the equator, negative south of it. */
    fun forward(p: LatLon, zone: Int): Pair<Double, Double> {
        val lon0 = Math.toRadians(centralMeridian(zone))
        val phi = Math.toRadians(p.lat)
        val n = A / sqrt(1 - E2 * sin(phi) * sin(phi))
        val t = tan(phi) * tan(phi)
        val c = EP2 * cos(phi) * cos(phi)
        val a = cos(phi) * (Math.toRadians(p.lon) - lon0)
        val easting = K0 * n * (a + (1 - t + c) * a * a * a / 6 + (5 - 18 * t + t * t + 72 * c - 58 * EP2) * a.pow(5) / 120) + FALSE_EASTING
        val northing = K0 * (
            meridianArc(phi) + n * tan(phi) * (
                a * a / 2 + (5 - t + 9 * c + 4 * c * c) * a.pow(4) / 24 +
                    (61 - 58 * t + t * t + 600 * c - 330 * EP2) * a.pow(6) / 720
                )
            )
        return easting to northing
    }

    /** The point at [easting], [northing] (from the equator, negative south) in [zone]. */
    fun inverse(easting: Double, northing: Double, zone: Int): LatLon {
        val m = northing / K0
        val mu = m / (A * (1 - E2 / 4 - 3 * E2 * E2 / 64 - 5 * E2 * E2 * E2 / 256))
        val e1 = (1 - sqrt(1 - E2)) / (1 + sqrt(1 - E2))
        val phi1 = mu + (3 * e1 / 2 - 27 * e1.pow(3) / 32) * sin(2 * mu) +
            (21 * e1 * e1 / 16 - 55 * e1.pow(4) / 32) * sin(4 * mu) +
            (151 * e1.pow(3) / 96) * sin(6 * mu) + (1097 * e1.pow(4) / 512) * sin(8 * mu)
        val n1 = A / sqrt(1 - E2 * sin(phi1) * sin(phi1))
        val t1 = tan(phi1) * tan(phi1)
        val c1 = EP2 * cos(phi1) * cos(phi1)
        val r1 = A * (1 - E2) / (1 - E2 * sin(phi1) * sin(phi1)).pow(1.5)
        val d = (easting - FALSE_EASTING) / (n1 * K0)
        val lat = phi1 - (n1 * tan(phi1) / r1) * (
            d * d / 2 - (5 + 3 * t1 + 10 * c1 - 4 * c1 * c1 - 9 * EP2) * d.pow(4) / 24 +
                (61 + 90 * t1 + 298 * c1 + 45 * t1 * t1 - 252 * EP2 - 3 * c1 * c1) * d.pow(6) / 720
            )
        val lon = (d - (1 + 2 * t1 + c1) * d.pow(3) / 6 + (5 - 2 * c1 + 28 * t1 - 3 * c1 * c1 + 8 * EP2 + 24 * t1 * t1) * d.pow(5) / 120) / cos(phi1)
        return LatLon(Math.toDegrees(lat), centralMeridian(zone) + Math.toDegrees(lon))
    }

    /** The standard 6° zone of [lon], 1 to 60. */
    fun zone(lon: Double): Int = (floor((lon + 180) / 6).toInt() + 1).coerceIn(1, 60)

    fun west(zone: Int): Double = (zone - 1) * 6.0 - 180.0

    fun centralMeridian(zone: Int): Double = west(zone) + 3.0

    private fun meridianArc(phi: Double): Double = A * (
        (1 - E2 / 4 - 3 * E2 * E2 / 64 - 5 * E2 * E2 * E2 / 256) * phi -
            (3 * E2 / 8 + 3 * E2 * E2 / 32 + 45 * E2 * E2 * E2 / 1024) * sin(2 * phi) +
            (15 * E2 * E2 / 256 + 45 * E2 * E2 * E2 / 1024) * sin(4 * phi) -
            (35 * E2 * E2 * E2 / 3072) * sin(6 * phi)
        )

    private const val A = 6_378_137.0
    private const val F = 1 / 298.257223563
    private const val E2 = F * (2 - F)
    private const val EP2 = E2 / (1 - E2)
    private const val K0 = 0.9996
    private const val FALSE_EASTING = 500_000.0
}

/**
 * Grid lines over the map, as on paper maps: a UTM grid (the squares of IGM, Tabacco and
 * Kompass maps, and what the SOS screen gives) or latitude and longitude. Made for the area
 * on screen, so it works anywhere, offline.
 */
object CoordinateGrid {
    enum class Kind { Off, Utm, LatLon }

    /**
     * One grid line: its points and its number. [northSouth]: it runs north–south (an
     * easting or a meridian), so its number goes at the top of the screen; the others at
     * the left edge.
     */
    data class Line(val points: List<LatLon>, val label: String, val northSouth: Boolean) {
        /** Where the line crosses latitude [lat] (north–south lines) or longitude [lon] (the others). */
        fun crossing(lat: Double, lon: Double): LatLon? {
            for (i in 1 until points.size) {
                val a = points[i - 1]
                val b = points[i]
                if (northSouth) {
                    if ((a.lat - lat) * (b.lat - lat) <= 0 && a.lat != b.lat) {
                        val t = (lat - a.lat) / (b.lat - a.lat)
                        return LatLon(lat, a.lon + (b.lon - a.lon) * t)
                    }
                } else if ((a.lon - lon) * (b.lon - lon) <= 0 && a.lon != b.lon) {
                    val t = (lon - a.lon) / (b.lon - a.lon)
                    return LatLon(a.lat + (b.lat - a.lat) * t, lon)
                }
            }
            return null
        }
    }

    /** UTM grid steps, in metres. */
    private val UTM_STEPS = listOf(1_000, 2_000, 5_000, 10_000, 20_000, 50_000, 100_000)

    /** Latitude and longitude steps, in minutes. */
    private val ANGLE_STEPS = listOf(1, 2, 5, 10, 15, 30, 60, 120, 300, 600)

    /** Lines no closer than this on screen. */
    private const val MIN_GAP_PX = 90.0

    /** Points per line: UTM lines curve a little in latitude and longitude. */
    private const val SAMPLES = 24

    /** The step for [metersPerPixel]: the smallest that keeps lines [MIN_GAP_PX] apart. */
    fun utmStep(metersPerPixel: Double): Int = UTM_STEPS.firstOrNull { it / metersPerPixel >= MIN_GAP_PX } ?: UTM_STEPS.last()

    fun angleStepMinutes(metersPerPixel: Double): Int =
        ANGLE_STEPS.firstOrNull { it * METERS_PER_MINUTE / metersPerPixel >= MIN_GAP_PX } ?: ANGLE_STEPS.last()

    /**
     * The UTM grid in [box], [stepM] apart, with each zone's lines kept inside it; labels in
     * kilometres, as on paper maps. Nothing beyond 84°N and 80°S, where UTM stops.
     */
    fun utm(box: BoundingBox, stepM: Int): List<Line> {
        val south = box.south.coerceAtLeast(-80.0)
        val north = box.north.coerceAtMost(84.0)
        if (south >= north) return emptyList()
        val lines = ArrayList<Line>()
        for (zone in Utm.zone(box.west)..Utm.zone(box.east.coerceAtMost(179.999))) {
            val west = maxOf(box.west, Utm.west(zone))
            val east = minOf(box.east, Utm.west(zone) + 6.0)
            if (west >= east) continue
            // The zone part's extent in UTM: its corners and edge middles, the edges bulge.
            val edge = listOf(south, (south + north) / 2, north).flatMap { lat -> listOf(west, (west + east) / 2, east).map { LatLon(lat, it) } }
            val utm = edge.map { Utm.forward(it, zone) }
            val e0 = utm.minOf { it.first }
            val e1 = utm.maxOf { it.first }
            val n0 = utm.minOf { it.second }
            val n1 = utm.maxOf { it.second }
            fun keep(points: List<LatLon>) = clip(points, west, east, south, north)
            var e = ceil(e0 / stepM) * stepM
            while (e <= e1) {
                val points = (0..SAMPLES).map { i -> Utm.inverse(e, n0 + (n1 - n0) * i / SAMPLES, zone) }
                for (part in keep(points)) lines += Line(part, km(e), northSouth = true)
                e += stepM
            }
            var n = ceil(n0 / stepM) * stepM
            while (n <= n1) {
                val points = (0..SAMPLES).map { i -> Utm.inverse(e0 + (e1 - e0) * i / SAMPLES, n, zone) }
                // South of the equator, northings count from 10 000 km.
                for (part in keep(points)) lines += Line(part, km(if (n < 0) n + 10_000_000 else n), northSouth = false)
                n += stepM
            }
        }
        return lines
    }

    /** Meridians and parallels in [box], [stepMinutes] apart, labelled in degrees and minutes. */
    fun latLon(box: BoundingBox, stepMinutes: Int): List<Line> {
        val step = stepMinutes / 60.0
        val lines = ArrayList<Line>()
        var lat = ceil(box.south / step) * step
        while (lat <= box.north) {
            lines += Line(listOf(LatLon(lat, box.west), LatLon(lat, box.east)), angle(lat, 'N', 'S'), northSouth = false)
            lat += step
        }
        var lon = ceil(box.west / step) * step
        while (lon <= box.east) {
            lines += Line(listOf(LatLon(box.south, lon), LatLon(box.north, lon)), angle(lon, 'E', 'W'), northSouth = true)
            lon += step
        }
        return lines
    }

    /** "4982" for 4 982 000 m: the grid's kilometre number. */
    private fun km(meters: Double): String = (meters / 1000).roundToInt().toString()

    /** "46°30′N": whole minutes, as the grid lines fall on them. */
    internal fun angle(value: Double, positive: Char, negative: Char): String {
        val totalMinutes = (abs(value) * 60).roundToInt()
        val degrees = totalMinutes / 60
        val minutes = totalMinutes % 60
        val hemisphere = if (value >= 0) positive else negative
        return if (minutes == 0) {
            String.format(Locale.ROOT, "%d°%c", degrees, hemisphere)
        } else {
            String.format(Locale.ROOT, "%d°%02d′%c", degrees, minutes, hemisphere)
        }
    }

    /**
     * The parts of [points] inside the longitude band [west]–[east] and latitude band
     * [south]–[north], cut where they cross out (by straight interpolation between samples).
     */
    private fun clip(points: List<LatLon>, west: Double, east: Double, south: Double, north: Double): List<List<LatLon>> {
        fun inside(p: LatLon) = p.lon in west..east && p.lat in south..north
        fun cut(a: LatLon, b: LatLon): LatLon {
            // The fraction of the way from a (inside) to b (outside) where it leaves.
            var t = 1.0
            if (b.lon > east) t = minOf(t, (east - a.lon) / (b.lon - a.lon))
            if (b.lon < west) t = minOf(t, (west - a.lon) / (b.lon - a.lon))
            if (b.lat > north) t = minOf(t, (north - a.lat) / (b.lat - a.lat))
            if (b.lat < south) t = minOf(t, (south - a.lat) / (b.lat - a.lat))
            return LatLon(a.lat + (b.lat - a.lat) * t, a.lon + (b.lon - a.lon) * t)
        }
        val parts = ArrayList<List<LatLon>>()
        var part = ArrayList<LatLon>()
        for (i in points.indices) {
            val p = points[i]
            val previous = points.getOrNull(i - 1)
            if (inside(p)) {
                if (part.isEmpty() && previous != null) part += cut(p, previous)
                part += p
            } else if (part.isNotEmpty()) {
                part += cut(points[i - 1], p)
                parts += part
                part = ArrayList()
            }
        }
        if (part.size >= 2) parts += part
        return parts.filter { it.size >= 2 }
    }

    private const val METERS_PER_MINUTE = 1852.0
}
