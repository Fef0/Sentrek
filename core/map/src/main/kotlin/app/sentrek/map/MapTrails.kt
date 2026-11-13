package app.sentrek.map

import app.sentrek.geo.BoundingBox
import app.sentrek.geo.LatLon
import app.sentrek.geo.plan.TrailLine
import org.maplibre.android.maps.Style
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.android.style.sources.VectorSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.LineString
import org.maplibre.geojson.MultiLineString
import org.maplibre.geojson.Point

/**
 * Walkable lines the map already has, for the planner to route along: no extra download,
 * offline wherever the map is. They come from the base tiles (OpenMapTiles "transportation")
 * and, in downloaded areas, the hiking overlay, which has alpine routes the base tiles leave
 * out. Only tiles the map has loaded are seen: roughly the area on screen.
 */
object MapTrails {
    private const val SOURCE = "openmaptiles"
    private const val LAYER = "transportation"

    /**
     * Cost per metre by class: paths, tracks and quiet roads the same, so the route takes the
     * road when it's shorter; busy roads a little more. "Avoid roads" leaves out all but paths
     * and tracks.
     */
    private val COST = mapOf(
        "path" to 1.0,
        "track" to 1.0,
        "minor" to 1.0,
        "service" to 1.0,
        "tertiary" to 1.1,
        "secondary" to 1.3,
        "primary" to 1.5,
    )

    private val NOT_ROADS = setOf("path", "track")

    private const val FERRATA_COST = 3.0

    /** Must run on the main thread (MapLibre). */
    fun lines(style: Style, box: BoundingBox): List<TrailLine> {
        val base = style.getSourceAs<VectorSource>(SOURCE)?.querySourceFeatures(arrayOf(LAYER), null).orEmpty()
            .flatMap { feature ->
                val type = feature.getStringProperty("class")
                val cost = COST[type] ?: return@flatMap emptyList()
                lines(feature, box, cost, road = type !in NOT_ROADS)
            }
        // Overlay trails are all footpaths, but via ferratas: taken only when there's no
        // walking way round, since they need gear. Its points are skipped by lines().
        val overlay = style.sources.filter { MapOverlays.isOverlaySource(it.id) }
            .filterIsInstance<GeoJsonSource>()
            .flatMap { source ->
                source.querySourceFeatures(null).flatMap { feature ->
                    val ferrata = feature.hasProperty("f")
                    // The SAC grade, for the "easiest" way; numbers may come back as doubles.
                    val grade = if (feature.hasProperty("s")) feature.getNumberProperty("s")?.toInt() else null
                    lines(feature, box, if (ferrata) FERRATA_COST else 1.0, grade, ferrata)
                }
            }
        return base + overlay
    }

    private fun lines(feature: Feature, box: BoundingBox, cost: Double, grade: Int? = null, ferrata: Boolean = false, road: Boolean = false): List<TrailLine> =
        when (val g = feature.geometry()) {
            is LineString -> listOf(g.coordinates())
            is MultiLineString -> g.coordinates()
            else -> emptyList()
        }.mapNotNull { coordinates ->
            val points = coordinates.map(::latLon)
            if (points.size >= 2 && overlaps(points, box)) TrailLine(points, cost, grade, ferrata, road) else null
        }

    private fun overlaps(points: List<LatLon>, box: BoundingBox) =
        points.maxOf { it.lat } >= box.south && points.minOf { it.lat } <= box.north &&
            points.maxOf { it.lon } >= box.west && points.minOf { it.lon } <= box.east

    private fun latLon(p: Point) = LatLon(p.latitude().coerceIn(-90.0, 90.0), p.longitude().coerceIn(-180.0, 180.0))
}
