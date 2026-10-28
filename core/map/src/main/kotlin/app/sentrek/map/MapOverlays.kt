package app.sentrek.map

import app.sentrek.geo.RegionCell
import app.sentrek.geo.overlay.OverpassOverlay
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
import org.maplibre.android.style.expressions.Expression.has
import org.maplibre.android.style.expressions.Expression.interpolate
import org.maplibre.android.style.expressions.Expression.linear
import org.maplibre.android.style.expressions.Expression.literal
import org.maplibre.android.style.expressions.Expression.lt
import org.maplibre.android.style.expressions.Expression.match
import org.maplibre.android.style.expressions.Expression.not
import org.maplibre.android.style.expressions.Expression.rgb
import org.maplibre.android.style.expressions.Expression.step
import org.maplibre.android.style.expressions.Expression.subtract
import org.maplibre.android.style.expressions.Expression.switchCase
import org.maplibre.android.style.expressions.Expression.toColor
import org.maplibre.android.style.expressions.Expression.within
import org.maplibre.android.style.expressions.Expression.stop
import org.maplibre.android.style.expressions.Expression.zoom
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory.iconAllowOverlap
import org.maplibre.android.style.layers.PropertyFactory.iconImage
import org.maplibre.android.style.layers.PropertyFactory.iconRotationAlignment
import org.maplibre.android.style.layers.PropertyFactory.lineCap
import org.maplibre.android.style.layers.PropertyFactory.lineColor
import org.maplibre.android.style.layers.PropertyFactory.lineDasharray
import org.maplibre.android.style.layers.PropertyFactory.lineJoin
import org.maplibre.android.style.layers.PropertyFactory.lineOpacity
import org.maplibre.android.style.layers.PropertyFactory.lineSortKey
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
import org.maplibre.android.style.layers.PropertyFactory.symbolPlacement
import org.maplibre.android.style.layers.PropertyFactory.symbolSortKey
import org.maplibre.android.style.layers.PropertyFactory.symbolSpacing
import org.maplibre.android.style.layers.PropertyFactory.textAnchor
import org.maplibre.android.style.layers.PropertyFactory.textColor
import org.maplibre.android.style.layers.PropertyFactory.textField
import org.maplibre.android.style.layers.PropertyFactory.textFont
import org.maplibre.android.style.layers.PropertyFactory.textHaloColor
import org.maplibre.android.style.layers.PropertyFactory.textHaloWidth
import org.maplibre.android.style.layers.PropertyFactory.textOffset
import org.maplibre.android.style.layers.PropertyFactory.textOptional
import org.maplibre.android.style.layers.PropertyFactory.textSize
import org.maplibre.android.style.layers.PropertyValue
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
 *    - every footpath and track, from zoom 10 (the base tiles only have them from 12).
 *      Inside downloaded areas the base tiles' own paths and tracks are hidden, so the
 *      overlay draws them all, at every zoom;
 *    - trail difficulty, coloured like Swiss waymarking: T1 yellow, T2–T3 red-white-red,
 *      T4–T6 blue-white-blue; faint trails get sparser dashes. Ungraded paths and tracks
 *      look like the base style's own;
 *    - hiking points (huts, water, caves, guideposts, crosses…) as icons, from lower zooms
 *      than the base tiles allow. Inside downloaded areas the base tiles' own copies of these
 *      points are hidden, so nothing is drawn twice;
 *    - waymarked routes, under the trails like the base style's, never broken (roads
 *      included) and with their waymark along the line from zoom 12. The base tiles' own
 *      routes are hidden there.
 */
object MapOverlays {
    private const val RASTER = "import-"
    private const val TRAILS = "trails-"
    private const val CLEAR = "-clear"
    private const val FAINT = "-faint"
    private const val OVERVIEW = "-overview"
    private const val PLAIN_PATH = "-path"
    private const val PLAIN_TRACK = "-track"

    /** Thin solid lines below this, dashed ones (like the base style's) from here. */
    private const val DETAIL_ZOOM = 12f
    private const val OVERVIEW_ZOOM = 10f
    private const val POINTS = "-points-z"
    private const val ROUTES = "-routes"
    private const val MARKS = "-marks"
    private const val MARKS_ZOOM = 12f

    /** From here in, point icons may overlap (as the base style's, hiking.json). */
    private const val ICONS_OVERLAP_ZOOM = 13f

    /** Base style layers the overlay replaces inside downloaded areas (hiking.json). */
    private const val BASE_HIKING_POIS = "poi-hiking"
    private const val BASE_PATH = "path"
    private const val BASE_TRACK = "track"
    private const val BASE_ROUTE = "hiking-route"
    private const val BASE_ROUTE_REF = "hiking-route-ref"
    private const val PATH_RED = "#c0262d"
    private const val TRACK_BROWN = "#8b5a2b"
    private val baseFilters = WeakHashMap<Style, MutableMap<String, Expression>>()

    /** First label layer of the hiking style. */
    private const val BELOW_LAYER = "water-name"

    internal fun isPointLayer(id: String) = id.startsWith(TRAILS) && id.contains(POINTS)

    /** A downloaded area's hiking overlay (trails with difficulty, and points). */
    internal fun isOverlaySource(id: String) = id.startsWith(TRAILS)

    internal fun isTrailLayer(id: String) =
        id.startsWith(TRAILS) && listOf(CLEAR, FAINT, OVERVIEW, PLAIN_PATH, PLAIN_TRACK).any(id::endsWith)

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
            // The base tiles carry paths only from zoom 12; the overlay has every path, so in
            // downloaded areas trails show from further out.
            style.addLayerBelow(routeLayer(style, sourceId, sourceId + ROUTES), BELOW_LAYER)
            style.addLayerBelow(overviewLayer(style, sourceId, sourceId + OVERVIEW, showDifficulty), BELOW_LAYER)
            style.addLayerBelow(plainLayer(style, sourceId, sourceId + PLAIN_TRACK, track = true, showDifficulty), BELOW_LAYER)
            style.addLayerBelow(plainLayer(style, sourceId, sourceId + PLAIN_PATH, track = false, showDifficulty), BELOW_LAYER)
            if (showDifficulty) {
                // Visibility 0 (unknown) to 3 (intermediate) is a trail you can follow.
                style.addLayerBelow(trailLayer(sourceId, sourceId + CLEAR, faint = false), BELOW_LAYER)
                style.addLayerBelow(trailLayer(sourceId, sourceId + FAINT, faint = true), BELOW_LAYER)
            }
            // Below every label: names and points win the space, the marks repeat anyway.
            style.addLayerBelow(markLayer(sourceId, sourceId + MARKS), BELOW_LAYER)
            // One layer per zoom tier (filters can't use zoom). Symbols collide with base labels
            // whatever their order, so these can sit on top.
            for ((minZoom, kinds) in PoiKind.entries.filter { it.onMap }.groupBy { it.minZoom }) {
                PlaceLayers.addBelow(style, pointLayer(sourceId, sourceId + POINTS + minZoom, minZoom, kinds))
            }
        }
        for (layer in listOf(BASE_HIKING_POIS, BASE_PATH, BASE_TRACK, BASE_ROUTE, BASE_ROUTE_REF)) hideInDownloaded(style, layer, overlays.keys)
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
                // Close in, a point never hides behind another: only its name gives way.
                iconAllowOverlap(step(zoom(), literal(false), stop(ICONS_OVERLAP_ZOOM, literal(true)))),
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
     * Hides a base layer's features inside cells whose overlay is loaded: the overlay draws
     * them already, from lower zooms. Outside downloaded areas nothing changes.
     */
    private fun hideInDownloaded(style: Style, layerId: String, cellIds: Collection<String>) {
        val layer = style.getLayer(layerId)
        val (filter, setFilter) = when (layer) {
            is SymbolLayer -> layer.filter to layer::setFilter
            is LineLayer -> layer.filter to layer::setFilter
            else -> return
        }
        val original = baseFilters.getOrPut(style) { HashMap() }.getOrPut(layerId) { filter ?: literal(true) }
        val cells = cellIds.mapNotNull(RegionCell::parse)
        if (cells.isEmpty()) {
            setFilter(original)
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
        setFilter(all(original, not(inDownloaded)))
    }

    /** The base style's colour for [layerId], as currently set (the night map changes it). */
    private fun baseColor(style: Style, layerId: String, fallback: String) =
        (style.getLayer(layerId) as? LineLayer)?.lineColor?.value as? String ?: fallback

    /** Ungraded lines: tracks (`t`) brown, paths red, like the base style's own. */
    private fun plainColor(style: Style) = switchCase(
        has("t"), toColor(literal(baseColor(style, BASE_TRACK, TRACK_BROWN))),
        toColor(literal(baseColor(style, BASE_PATH, PATH_RED))),
    )

    private fun difficultyColor() = step(
        get("s"), rgb(230, 175, 0),
        stop(2, rgb(200, 30, 40)),
        stop(4, rgb(30, 80, 200)),
    )

    /** Thin solid lines for zoomed-out views, where dashes would just be noise. */
    private fun overviewLayer(style: Style, sourceId: String, layerId: String, showDifficulty: Boolean) =
        LineLayer(layerId, sourceId).apply {
            minZoom = OVERVIEW_ZOOM
            maxZoom = DETAIL_ZOOM
            setFilter(trailLines())
            setProperties(
                lineColor(if (showDifficulty) switchCase(has("s"), difficultyColor(), plainColor(style)) else plainColor(style)),
                lineWidth(interpolate(linear(), zoom(), stop(OVERVIEW_ZOOM, 0.6f), stop(DETAIL_ZOOM, 1.6f))),
                lineOpacity(interpolate(linear(), zoom(), stop(OVERVIEW_ZOOM, 0.7f), stop(DETAIL_ZOOM, 0.9f))),
            )
        }

    /** Paths or tracks without a grade (all of them without difficulty colours), dashed like the base style's. */
    private fun plainLayer(style: Style, sourceId: String, layerId: String, track: Boolean, showDifficulty: Boolean) =
        LineLayer(layerId, sourceId).apply {
            minZoom = DETAIL_ZOOM
            val kind = if (track) has("t") else not(has("t"))
            val lines = all(trailLines(), kind)
            setFilter(if (showDifficulty) all(lines, not(has("s"))) else lines)
            setProperties(
                lineColor(plainColor(style)),
                lineWidth(interpolate(linear(), zoom(), stop(DETAIL_ZOOM, 1.5f), stop(14, 2.5f), stop(17, 4f))),
                lineDasharray(if (track) arrayOf(4f, 2f) else arrayOf(3f, 1.5f)),
            )
        }

    /** Paths and tracks, not the route lines laid over them. */
    private fun trailLines() = all(eq(geometryType(), literal("LineString")), not(has(OverpassOverlay.NETWORK)))

    /** Routes look like the base style's (night colours included): its paint is copied. */
    private fun routeLayer(style: Style, sourceId: String, layerId: String) =
        LineLayer(layerId, sourceId).apply {
            val base = style.getLayer(BASE_ROUTE) as? LineLayer
            minZoom = base?.minZoom ?: OVERVIEW_ZOOM
            setFilter(has(OverpassOverlay.NETWORK))
            base?.let { b ->
                val paint = listOf(b.lineColor, b.lineOpacity, b.lineWidth)
                    .mapNotNull { p -> (if (p.isExpression) p.expression else p.value)?.let { PropertyValue(p.name, it) } }
                setProperties(*paint.toTypedArray())
            }
            // The most important route on top where they share a path.
            setProperties(lineCap(Property.LINE_CAP_ROUND), lineJoin(Property.LINE_JOIN_ROUND), lineSortKey(get("w")))
        }

    /** The route's waymark along its line, the most important route's first. */
    private fun markLayer(sourceId: String, layerId: String) =
        SymbolLayer(layerId, sourceId).apply {
            minZoom = MARKS_ZOOM
            setFilter(has("m"))
            setProperties(
                symbolPlacement(Property.SYMBOL_PLACEMENT_LINE),
                symbolSpacing(220f),
                symbolSortKey(subtract(literal(10), get("w"))),
                iconImage(concat(literal(RouteMarks.PREFIX), get("m"))),
                iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_VIEWPORT),
            )
        }

    private fun trailLayer(sourceId: String, layerId: String, faint: Boolean) =
        LineLayer(layerId, sourceId).apply {
            minZoom = DETAIL_ZOOM
            val visibility = if (faint) gte(get("v"), literal(4)) else lt(get("v"), literal(4))
            setFilter(all(eq(geometryType(), literal("LineString")), has("s"), visibility))
            setProperties(
                lineColor(difficultyColor()),
                lineWidth(interpolate(linear(), zoom(), stop(DETAIL_ZOOM, 1.8f), stop(14, 3f), stop(17, 5f))),
                lineDasharray(if (faint) arrayOf(1f, 2.5f) else arrayOf(3f, 1.5f)),
                lineCap(Property.LINE_CAP_BUTT),
            )
        }
}
