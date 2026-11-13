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

    /** Cost per metre by class: trails first, quiet roads if needed, busy roads last. */
    private val COST = mapOf(
        "path" to 1.0,
        "track" to 1.0,
        "minor" to 1.3,
        "service" to 1.3,
        "tertiary" to 1.6,
        "secondary" to 2.5,
        "primary" to 3.0,
    )

    private const val FERRATA_COST = 3.0

    /** Must run on the main thread (MapLibre). */
    fun lines(style: Style, box: BoundingBox): List<TrailLine> {
        val base = style.getSourceAs<VectorSource>(SOURCE)?.querySourceFeatures(arrayOf(LAYER), null).orEmpty()
            .flatMap { feature ->
                val cost = COST[feature.getStringProperty("class")] ?: return@flatMap emptyList()
                lines(feature, box, cost)
            }
        // Overlay trails are all footpaths, but via ferratas: taken only when there's no
        // walking way round, since they need gear. Its points are skipped by lines().
        val overlay = style.sources.filter { MapOverlays.isOverlaySource(it.id) }
            .filterIsInstance<GeoJsonSource>()
            .flatMap { source -> source.querySourceFeatures(null).flatMap { lines(it, box, if (it.hasProperty("f")) FERRATA_COST else 1.0) } }
        return base + overlay
    }

    private fun lines(feature: Feature, box: BoundingBox, cost: Double): List<TrailLine> =
        when (val g = feature.geometry()) {
            is LineString -> listOf(g.coordinates())
            is MultiLineString -> g.coordinates()
            else -> emptyList()
        }.mapNotNull { coordinates ->
            val points = coordinates.map(::latLon)
            if (points.size >= 2 && overlaps(points, box)) TrailLine(points, cost) else null
        }

    private fun overlaps(points: List<LatLon>, box: BoundingBox) =
        points.maxOf { it.lat } >= box.south && points.minOf { it.lat } <= box.north &&
            points.maxOf { it.lon } >= box.west && points.minOf { it.lon } <= box.east

    private fun latLon(p: Point) = LatLon(p.latitude().coerceIn(-90.0, 90.0), p.longitude().coerceIn(-180.0, 180.0))
}
