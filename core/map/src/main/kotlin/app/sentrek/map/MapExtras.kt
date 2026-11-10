package app.sentrek.map

import app.sentrek.geo.overlay.MapKind
import app.sentrek.geo.overlay.OverpassExtras
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.expressions.Expression.all
import org.maplibre.android.style.expressions.Expression.coalesce
import org.maplibre.android.style.expressions.Expression.eq
import org.maplibre.android.style.expressions.Expression.geometryType
import org.maplibre.android.style.expressions.Expression.get
import org.maplibre.android.style.expressions.Expression.has
import org.maplibre.android.style.expressions.Expression.interpolate
import org.maplibre.android.style.expressions.Expression.linear
import org.maplibre.android.style.expressions.Expression.literal
import org.maplibre.android.style.expressions.Expression.match
import org.maplibre.android.style.expressions.Expression.not
import org.maplibre.android.style.expressions.Expression.stop
import org.maplibre.android.style.expressions.Expression.subtract
import org.maplibre.android.style.expressions.Expression.zoom
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.Layer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory.fillColor
import org.maplibre.android.style.layers.PropertyFactory.fillOpacity
import org.maplibre.android.style.layers.PropertyFactory.lineCap
import org.maplibre.android.style.layers.PropertyFactory.lineColor
import org.maplibre.android.style.layers.PropertyFactory.lineDasharray
import org.maplibre.android.style.layers.PropertyFactory.lineJoin
import org.maplibre.android.style.layers.PropertyFactory.lineOpacity
import org.maplibre.android.style.layers.PropertyFactory.lineSortKey
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
import org.maplibre.android.style.layers.PropertyFactory.symbolPlacement
import org.maplibre.android.style.layers.PropertyFactory.symbolSortKey
import org.maplibre.android.style.layers.PropertyFactory.textColor
import org.maplibre.android.style.layers.PropertyFactory.textField
import org.maplibre.android.style.layers.PropertyFactory.textFont
import org.maplibre.android.style.layers.PropertyFactory.textHaloColor
import org.maplibre.android.style.layers.PropertyFactory.textHaloWidth
import org.maplibre.android.style.layers.PropertyFactory.textSize
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import java.io.File
import java.net.URI
import java.util.WeakHashMap

/**
 * What the cycling and winter maps add to the hiking one, drawn only for the map chosen:
 * - [MapKind.Winter]: pistes from the downloaded areas' files ([OverpassExtras]), coloured as
 *   on European piste maps (green, blue, red, black; freeride orange), cross-country, ski
 *   touring and sledging runs dashed, piste areas lightly filled; plus the names of lifts.
 * - [MapKind.Cycling]: waymarked cycle routes (under the trails, like hiking routes), and
 *   cycleways from the base tiles, solid blue.
 * The hiking routes and the trail difficulty colours are hidden on both ([MapOverlays.sync]).
 * The winter map also has its own white colours ([WinterStyle]).
 */
object MapExtras {
    private const val PREFIX = "extra-"
    private const val BASE_SOURCE = "openmaptiles"

    /** Lines go above the trails and below every label, as the hiking overlay's. */
    private const val BELOW_LABELS = "water-name"

    /** Under the base tiles' paths and the hiking overlay, as the hiking routes. */
    private const val BELOW_TRAILS = "hiking-route"

    private val loadedAt = WeakHashMap<Style, MutableMap<String, Long>>()

    /** [files]: cell id → that area's file of [kind]. Nothing for [MapKind.Hiking]. */
    fun sync(style: Style, kind: MapKind, files: Map<String, File>, night: Boolean) {
        WinterStyle.apply(style, winter = kind == MapKind.Winter && !night)
        style.layers.filter { it.id.startsWith(PREFIX) }.forEach { style.removeLayer(it) }
        val wanted = if (kind == MapKind.Hiking) emptySet() else files.keys.map { sourceId(kind, it) }.toSet()
        // Another map's files aren't kept in memory.
        style.sources.filter { it.id.startsWith(PREFIX) && it.id !in wanted }.forEach { style.removeSource(it) }
        loadedAt[style]?.keys?.retainAll(wanted)

        when (kind) {
            MapKind.Hiking -> Unit
            MapKind.Winter -> {
                for ((id, file) in files) {
                    val source = load(style, sourceId(kind, id), file)
                    addBelow(style, pisteAreas(source, night), BELOW_TRAILS)
                    addBelow(style, pisteLines(source, night), BELOW_LABELS)
                    addBelow(style, pisteNames(source), BELOW_LABELS)
                }
                addBelow(style, liftNames(), BELOW_LABELS)
            }
            MapKind.Cycling -> {
                addBelow(style, cycleways(), BELOW_LABELS)
                for ((id, file) in files) {
                    val source = load(style, sourceId(kind, id), file)
                    addBelow(style, cycleRoutes(source), BELOW_TRAILS)
                    addBelow(style, cycleRefs(source), BELOW_LABELS)
                }
            }
        }
    }

    private fun sourceId(kind: MapKind, cellId: String) = "$PREFIX${kind.key}-$cellId"

    private fun load(style: Style, sourceId: String, file: File): String {
        // MapLibre needs "file://" + absolute path (see MapOverlays).
        val uri = URI("file://${file.absolutePath}")
        val loaded = loadedAt.getOrPut(style) { HashMap() }
        val source = style.getSourceAs<GeoJsonSource>(sourceId)
        if (source == null) {
            style.addSource(GeoJsonSource(sourceId, uri))
        } else if (loaded[sourceId] != file.lastModified()) {
            source.setUri(uri)
        }
        loaded[sourceId] = file.lastModified()
        return sourceId
    }

    private fun addBelow(style: Style, layer: Layer, below: String) {
        if (style.getLayer(below) != null) style.addLayerBelow(layer, below) else style.addLayer(layer)
    }

    // Winter

    private fun downhillColor(night: Boolean): Expression = match(
        get("pd"), literal("#757575"),
        stop("novice", "#2e9d3a"),
        stop("easy", "#1e5bd8"),
        stop("intermediate", "#d32f2f"),
        // Black runs on the night map's dark ground: light grey.
        stop("advanced", if (night) "#e0e0e0" else "#111111"),
        stop("expert", if (night) "#e0e0e0" else "#111111"),
        stop("freeride", "#ef6c00"),
        stop("extreme", "#ef6c00"),
    )

    private fun pisteColor(night: Boolean): Expression = match(
        get("pt"), literal("#757575"),
        stop("downhill", downhillColor(night)),
        stop("nordic", "#00897b"),
        stop("skitour", "#7b1fa2"),
        stop("sled", "#795548"),
        stop("sleigh", "#795548"),
        stop("hike", "#c0262d"),
    )

    private fun lines() = eq(geometryType(), literal("LineString"))

    private fun pisteAreas(source: String, night: Boolean) = FillLayer("$source-areas", source).apply {
        minZoom = 11f
        setFilter(eq(geometryType(), literal("Polygon")))
        setProperties(fillColor(pisteColor(night)), fillOpacity(0.15f))
    }

    /**
     * One layer, so dashes can't vary by kind (data-driven dasharray isn't supported):
     * downhill solid, the rest made thinner and lighter instead, and areas outlined.
     */
    private fun pisteLines(source: String, night: Boolean) = LineLayer("$source-lines", source).apply {
        minZoom = 10f
        setProperties(
            lineColor(pisteColor(night)),
            lineWidth(
                interpolate(
                    linear(), zoom(),
                    stop(10, match(get("pt"), literal(0.8f), stop("downhill", 1.2f))),
                    stop(14, match(get("pt"), literal(2.2f), stop("downhill", 3.2f), stop("connection", 1.2f))),
                    stop(17, match(get("pt"), literal(3.5f), stop("downhill", 5f), stop("connection", 2f))),
                ),
            ),
            lineOpacity(match(get("pt"), literal(0.85f), stop("downhill", 0.95f))),
            lineCap(Property.LINE_CAP_ROUND),
            lineJoin(Property.LINE_JOIN_ROUND),
            // Downhill on top where a ski tour or a sledge run shares it.
            lineSortKey(match(get("pt"), literal(0), stop("downhill", 2), stop("nordic", 1))),
        )
    }

    private fun pisteNames(source: String) = SymbolLayer("$source-names", source).apply {
        minZoom = 13f
        setFilter(all(has("n"), lines()))
        setProperties(
            symbolPlacement(Property.SYMBOL_PLACEMENT_LINE),
            textField(get("n")),
            textFont(arrayOf("Noto Sans Regular")),
            textSize(11f),
            textColor("#222222"),
            textHaloColor("#ffffff"),
            textHaloWidth(1.5f),
        )
    }

    /** The base tiles have lift names, but the hiking style doesn't show them. */
    private fun liftNames() = SymbolLayer("${PREFIX}lift-names", BASE_SOURCE).apply {
        sourceLayer = "transportation_name"
        minZoom = 13f
        setFilter(all(eq(get("class"), literal("aerialway")), has("name")))
        setProperties(
            symbolPlacement(Property.SYMBOL_PLACEMENT_LINE),
            textField(get("name")),
            textFont(arrayOf("Noto Sans Bold")),
            textSize(11f),
            textColor("#333333"),
            textHaloColor("#ffffff"),
            textHaloWidth(1.5f),
        )
    }

    // Cycling

    private fun cycleColor(): Expression = match(
        get(OverpassExtras.NETWORK), literal("#3949ab"),
        stop("icn", "#d81b60"),
        stop("ncn", "#d81b60"),
        stop("rcn", "#00838f"),
        stop("lcn", "#3949ab"),
        stop(OverpassExtras.MTB, "#a67c00"),
    )

    /** Wide and see-through, as the hiking routes: the trail on it stays readable. */
    private fun cycleRoutes(source: String) = LineLayer("$source-routes", source).apply {
        minZoom = 8f
        setFilter(lines())
        setProperties(
            lineColor(cycleColor()),
            lineOpacity(0.5f),
            lineWidth(interpolate(linear(), zoom(), stop(8, 1.5f), stop(14, 7f), stop(17, 11f))),
            lineCap(Property.LINE_CAP_ROUND),
            lineJoin(Property.LINE_JOIN_ROUND),
            lineSortKey(get("w")),
        )
    }

    private fun cycleRefs(source: String) = SymbolLayer("$source-refs", source).apply {
        minZoom = 12f
        setFilter(all(lines(), has("ref")))
        setProperties(
            symbolPlacement(Property.SYMBOL_PLACEMENT_LINE),
            symbolSortKey(subtract(literal(10), get("w"))),
            textField(coalesce(get("ref"), literal(""))),
            textFont(arrayOf("Noto Sans Bold")),
            textSize(12f),
            textColor("#ffffff"),
            textHaloColor(cycleColor()),
            textHaloWidth(2.5f),
        )
    }

    private fun cycleways() = LineLayer("${PREFIX}cycleways", BASE_SOURCE).apply {
        sourceLayer = "transportation"
        minZoom = 12f
        setFilter(all(eq(get("class"), literal("path")), eq(get("subclass"), literal("cycleway")), not(eq(get("brunnel"), literal("tunnel")))))
        setProperties(
            lineColor("#1565c0"),
            lineWidth(interpolate(linear(), zoom(), stop(12, 1f), stop(14, 2f), stop(17, 3.5f))),
        )
    }
}
