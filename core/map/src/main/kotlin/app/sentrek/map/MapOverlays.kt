package app.sentrek.map

import app.sentrek.geo.RegionCell
import app.sentrek.geo.overlay.PoiKind
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.expressions.Expression.all
import org.maplibre.android.style.expressions.Expression.any
import org.maplibre.android.style.expressions.Expression.coalesce
import org.maplibre.android.style.expressions.Expression.concat
import org.maplibre.android.style.expressions.Expression.eq
import org.maplibre.android.style.expressions.Expression.geometryType
import org.maplibre.android.style.expressions.Expression.get
import org.maplibre.android.style.expressions.Expression.gte
import org.maplibre.android.style.expressions.Expression.interpolate
import org.maplibre.android.style.expressions.Expression.linear
import org.maplibre.android.style.expressions.Expression.literal
import org.maplibre.android.style.expressions.Expression.lt
import org.maplibre.android.style.expressions.Expression.match
import org.maplibre.android.style.expressions.Expression.not
import org.maplibre.android.style.expressions.Expression.rgb
import org.maplibre.android.style.expressions.Expression.step
import org.maplibre.android.style.expressions.Expression.within
import org.maplibre.android.style.expressions.Expression.stop
import org.maplibre.android.style.expressions.Expression.zoom
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory.iconImage
import org.maplibre.android.style.layers.PropertyFactory.lineCap
import org.maplibre.android.style.layers.PropertyFactory.lineColor
import org.maplibre.android.style.layers.PropertyFactory.lineDasharray
import org.maplibre.android.style.layers.PropertyFactory.lineOpacity
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
import org.maplibre.android.style.layers.PropertyFactory.symbolSortKey
import org.maplibre.android.style.layers.PropertyFactory.textAnchor
import org.maplibre.android.style.layers.PropertyFactory.textColor
import org.maplibre.android.style.layers.PropertyFactory.textField
import org.maplibre.android.style.layers.PropertyFactory.textFont
import org.maplibre.android.style.layers.PropertyFactory.textHaloColor
import org.maplibre.android.style.layers.PropertyFactory.textHaloWidth
import org.maplibre.android.style.layers.PropertyFactory.textOffset
import org.maplibre.android.style.layers.PropertyFactory.textOptional
import org.maplibre.android.style.layers.PropertyFactory.textSize
import org.maplibre.android.style.layers.RasterLayer
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.android.style.sources.RasterSource
import org.maplibre.geojson.Point
import org.maplibre.geojson.Polygon
import java.io.File
import java.net.URI
import java.util.WeakHashMap

/** A raster map file imported by the user, e.g. `mbtiles:///…` or `pmtiles://file:///…`. */
data class RasterOverlay(val id: String, val url: String)

/**
 * Layers Sentrek adds on top of the base style, always in this order (bottom to top),
 * and all below the base labels so names stay readable:
 * 1. imported raster maps,
 * 2. the hiking overlay, one GeoJSON file per downloaded cell (see OverpassOverlay):
 *    - trail difficulty, coloured like Swiss waymarking: T1 yellow, T2–T3 red-white-red,
 *      T4–T6 blue-white-blue; faint trails get sparser dashes;
 *    - hiking points (huts, water, caves, guideposts, crosses…) as icons, from lower zooms
 *      than the base tiles allow. Inside downloaded areas the base tiles' own copies of these
 *      points are hidden, so nothing is drawn twice.
 */
object MapOverlays {
    private const val RASTER = "import-"
    private const val TRAILS = "trails-"
    private const val CLEAR = "-clear"
    private const val FAINT = "-faint"
    private const val OVERVIEW = "-overview"

    /** Where the base tiles' own paths begin, and the overlay's detailed trails with them. */
    private const val DETAIL_ZOOM = 12f
    private const val OVERVIEW_ZOOM = 9f
    private const val POINTS = "-points-z"

    /** Base style layer with the hiking points the overlay also carries (hiking.json). */
    private const val BASE_HIKING_POIS = "poi-hiking"
    private val baseFilters = WeakHashMap<Style, Expression>()

    /** First label layer of the hiking style. */
    private const val BELOW_LAYER = "water-name"

    internal fun isPointLayer(id: String) = id.startsWith(TRAILS) && id.contains(POINTS)

    /** A downloaded area's hiking overlay (trails with difficulty, and points). */
    internal fun isOverlaySource(id: String) = id.startsWith(TRAILS)

    internal fun isTrailLayer(id: String) =
        id.startsWith(TRAILS) && (id.endsWith(CLEAR) || id.endsWith(FAINT) || id.endsWith(OVERVIEW))

    /** [overlays]: cell id → overlay file. [showDifficulty] hides only the trail colours, not the points. */
    fun sync(style: Style, rasters: List<RasterOverlay>, overlays: Map<String, File>, showDifficulty: Boolean) {
        // Layers are cheap to rebuild and that keeps the order right; sources (parsed
        // GeoJSON, open tile files) are kept whenever possible.
        style.layers.filter { it.id.startsWith(RASTER) || it.id.startsWith(TRAILS) }.forEach { style.removeLayer(it) }

        val wanted = rasters.map { RASTER + it.id }.toSet() + overlays.keys.map { TRAILS + it }
        style.sources.filter { (it.id.startsWith(RASTER) || it.id.startsWith(TRAILS)) && it.id !in wanted }
            .forEach { style.removeSource(it) }

        for (raster in rasters) {
            val sourceId = RASTER + raster.id
            if (style.getSource(sourceId) == null) style.addSource(RasterSource(sourceId, raster.url, 256))
            style.addLayerBelow(RasterLayer(sourceId, sourceId), BELOW_LAYER)
        }
        for ((id, file) in overlays) {
            val sourceId = TRAILS + id
            if (style.getSource(sourceId) == null) {
                // MapLibre needs "file://" + absolute path; File.toURI() yields "file:/…", which it rejects.
                style.addSource(GeoJsonSource(sourceId, URI("file://${file.absolutePath}")))
            }
            // The base tiles carry paths only from zoom 12; the overlay has no such limit, so in
            // downloaded areas trails show from much further out.
            style.addLayerBelow(overviewLayer(sourceId, sourceId + OVERVIEW, showDifficulty), BELOW_LAYER)
            if (showDifficulty) {
                // Visibility 0 (unknown) to 3 (intermediate) is a trail you can follow.
                style.addLayerBelow(trailLayer(sourceId, sourceId + CLEAR, faint = false), BELOW_LAYER)
                style.addLayerBelow(trailLayer(sourceId, sourceId + FAINT, faint = true), BELOW_LAYER)
            }
            // One layer per zoom tier (filters can't use zoom). Symbols collide with base labels
            // whatever their order, so these can sit on top.
            for ((minZoom, kinds) in PoiKind.entries.groupBy { it.minZoom }) {
                PlaceLayers.addBelow(style, pointLayer(sourceId, sourceId + POINTS + minZoom, minZoom, kinds))
            }
        }
        hideBaseHikingPois(style, overlays.keys)
    }

    private fun pointLayer(sourceId: String, layerId: String, minZoom: Int, kinds: List<PoiKind>) =
        SymbolLayer(layerId, sourceId).apply {
            this.minZoom = minZoom.toFloat()
            val ofTier = match(get("k"), literal(false), *kinds.map { stop(literal(it.icon), literal(true)) }.toTypedArray())
            setFilter(all(eq(geometryType(), literal("Point")), ofTier))
            // Names two zoom levels after the icons, so overview zooms stay readable.
            val labelZoom = maxOf(minZoom + 2, 13)
            setProperties(
                iconImage(concat(literal(MapIcons.PREFIX), get("k"))),
                symbolSortKey(match(get("k"), literal(99), *PoiKind.entries.map { stop(literal(it.icon), literal(it.ordinal)) }.toTypedArray())),
                textField(step(zoom(), literal(""), stop(labelZoom, coalesce(get("n"), literal(""))))),
                textFont(arrayOf("Noto Sans Bold")),
                textSize(12f),
                textAnchor(Property.TEXT_ANCHOR_TOP),
                textOffset(arrayOf(0f, 1.1f)),
                textOptional(true),
                textColor(rgb(59, 42, 26)),
                textHaloColor(rgb(255, 255, 255)),
                textHaloWidth(1.8f),
            )
        }

    /**
     * Hides the base tiles' hiking points inside cells whose overlay is loaded: the overlay
     * draws them already, from lower zooms. Outside downloaded areas nothing changes.
     */
    private fun hideBaseHikingPois(style: Style, cellIds: Collection<String>) {
        val layer = style.getLayer(BASE_HIKING_POIS) as? SymbolLayer ?: return
        val original = baseFilters.getOrPut(style) { layer.filter ?: literal(true) }
        val cells = cellIds.mapNotNull(RegionCell::parse)
        if (cells.isEmpty()) {
            layer.setFilter(original)
            return
        }
        // One "within" per cell: the Android expression API only accepts a single Polygon
        // (a MultiPolygon, even as raw JSON, is parsed as Polygon and throws).
        val inDownloaded = any(
            *cells.map { cell ->
                val b = cell.bbox
                val ring = listOf(
                    Point.fromLngLat(b.west, b.south), Point.fromLngLat(b.east, b.south),
                    Point.fromLngLat(b.east, b.north), Point.fromLngLat(b.west, b.north),
                    Point.fromLngLat(b.west, b.south),
                )
                within(Polygon.fromLngLats(listOf(ring)))
            }.toTypedArray(),
        )
        layer.setFilter(all(original, not(inDownloaded)))
    }

    private fun difficultyColor() = step(
        get("s"), rgb(230, 175, 0),
        stop(2, rgb(200, 30, 40)),
        stop(4, rgb(30, 80, 200)),
    )

    /** Thin solid lines for zoomed-out views, where dashes would just be noise. */
    private fun overviewLayer(sourceId: String, layerId: String, showDifficulty: Boolean) =
        LineLayer(layerId, sourceId).apply {
            minZoom = OVERVIEW_ZOOM
            maxZoom = DETAIL_ZOOM
            setFilter(eq(geometryType(), literal("LineString")))
            setProperties(
                // Without difficulty colours: the red of the style's own paths (hiking.json).
                lineColor(if (showDifficulty) difficultyColor() else rgb(192, 38, 45)),
                lineWidth(interpolate(linear(), zoom(), stop(OVERVIEW_ZOOM, 0.7f), stop(DETAIL_ZOOM, 1.6f))),
                lineOpacity(interpolate(linear(), zoom(), stop(OVERVIEW_ZOOM, 0.7f), stop(DETAIL_ZOOM, 0.9f))),
            )
        }

    private fun trailLayer(sourceId: String, layerId: String, faint: Boolean) =
        LineLayer(layerId, sourceId).apply {
            minZoom = DETAIL_ZOOM
            val visibility = if (faint) gte(get("v"), literal(4)) else lt(get("v"), literal(4))
            setFilter(all(eq(geometryType(), literal("LineString")), visibility))
            setProperties(
                lineColor(difficultyColor()),
                lineWidth(interpolate(linear(), zoom(), stop(DETAIL_ZOOM, 1.8f), stop(14, 3f), stop(17, 5f))),
                lineDasharray(if (faint) arrayOf(1f, 2.5f) else arrayOf(3f, 1.5f)),
                lineCap(Property.LINE_CAP_BUTT),
            )
        }
}
