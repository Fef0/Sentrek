package app.sentrek.map

import org.maplibre.android.style.layers.PropertyFactory.textIgnorePlacement
import org.maplibre.android.style.layers.PropertyFactory.textAllowOverlap
import org.maplibre.android.style.layers.PropertyFactory.textColor
import org.maplibre.android.style.layers.PropertyFactory.textSize
import org.maplibre.android.style.layers.PropertyFactory.textFont
import org.maplibre.android.style.layers.PropertyFactory.textField
import org.maplibre.android.style.expressions.Expression.has
import org.maplibre.android.style.expressions.Expression.coalesce
import app.sentrek.geo.plan.PlanGeometry
import org.maplibre.android.style.layers.SymbolLayer
import app.sentrek.geo.LatLon
import app.sentrek.geo.plan.PlanLeg
import org.maplibre.android.style.expressions.Expression.eq
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression.get
import org.maplibre.android.style.expressions.Expression.literal
import org.maplibre.android.style.expressions.Expression.match
import org.maplibre.android.style.expressions.Expression.rgb
import org.maplibre.android.style.expressions.Expression.stop
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory.circleColor
import org.maplibre.android.style.layers.PropertyFactory.circleOpacity
import org.maplibre.android.style.layers.PropertyFactory.circleRadius
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth
import org.maplibre.android.style.layers.PropertyFactory.lineCap
import org.maplibre.android.style.layers.PropertyFactory.lineColor
import org.maplibre.android.style.layers.PropertyFactory.lineDasharray
import org.maplibre.android.style.layers.PropertyFactory.lineJoin
import org.maplibre.android.style.layers.PropertyFactory.lineOpacity
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
import org.maplibre.android.style.layers.PropertyFactory.iconImage
import org.maplibre.android.style.layers.PropertyFactory.symbolPlacement
import org.maplibre.android.style.layers.PropertyFactory.symbolSpacing
import org.maplibre.android.style.layers.PropertyFactory.visibility
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

/**
 * The route being planned: its legs (along the paths, or dashed where straight), a handle on
 * each point (start green, end red, the selected one bigger) and a small one in the middle of
 * each leg to drag out a new point.
 */
object PlanLayers {
    private const val LINE = "plan-line"
    private const val POINTS = "plan-points"
    private const val CASING = "-casing"
    private const val STRAIGHT = "-straight"
    private const val COLOR = 0xFFD81B60.toInt()

    /**
     * [sectionEnds]: the indices of the points that end a section, numbered on the map.
     * [arrows]: small arrows along the line, the way it goes, as on the saved tracks.
     */
    fun sync(
        style: Style,
        points: List<LatLon>,
        legs: List<PlanLeg>,
        selected: Int?,
        sectionEnds: List<Int> = emptyList(),
        arrows: Boolean = true,
    ) {
        val line = legs.flatMap { it.pieces }.filter { (points, _) -> points.size >= 2 }.map { (points, onPath) ->
            Feature.fromGeometry(LineString.fromLngLats(points.map { Point.fromLngLat(it.lon, it.lat) })).apply {
                addBooleanProperty("t", onPath)
            }
        }
        val handles = points.mapIndexed { i, p ->
            Feature.fromGeometry(Point.fromLngLat(p.lon, p.lat)).apply {
                val section = sectionEnds.indexOf(i)
                val role = when {
                    i == selected -> "selected"
                    i == 0 -> "start"
                    i == points.lastIndex && points.size > 1 -> "end"
                    section >= 0 -> "section"
                    else -> "point"
                }
                addStringProperty("r", role)
                if (section >= 0) addStringProperty("n", "${section + 1}")
            }
        } + legs.map { it.middle }.map { p ->
            Feature.fromGeometry(Point.fromLngLat(p.lon, p.lat)).apply { addStringProperty("r", "mid") }
        }
        setSource(style, LINE, line)
        setSource(style, POINTS, handles)
        if (style.getLayer(LINE) == null) addLayers(style)
        style.getLayer(ARROWS)?.setProperties(visibility(if (arrows) Property.VISIBLE else Property.NONE))
        // Over every track, whenever one was drawn after the plan: the route being made is
        // the one the user is looking at.
        if (points.isNotEmpty() && (style !== raisedStyle || PlaceLayers.added != raisedAt)) raise(style)
    }

    private var raisedStyle: Style? = null
    private var raisedAt = -1

    /** The plan's layers (and the other ways', over it) taken out and put back on top, below the places. */
    private fun raise(style: Style) {
        val ids = style.layers.map { it.id }
        val first = ids.indexOf(LINE + CASING)
        val lastTrack = ids.indexOfLast { it.startsWith("track-") || it.startsWith("goto") }
        if (first < 0 || lastTrack > first) {
            for (id in PLAN_LAYERS + EXTRA_LAYERS) style.removeLayer(id)
            addLayers(style)
            if (style.getSource(WAYS) != null) addExtraLayers(style)
        }
        raisedStyle = style
        raisedAt = PlaceLayers.added
    }

    /** Hides the plan without forgetting the layers (the planner was closed). */
    fun clear(style: Style) {
        sync(style, emptyList(), emptyList(), null)
        if (style.getSource(WAYS) != null) syncExtras(style, emptyList(), null, emptyList())
    }

    private const val NUMBERS = "plan-section-numbers"
    private const val ARROWS = "plan-arrows"
    private val PLAN_LAYERS = listOf(LINE + CASING, LINE, LINE + STRAIGHT, ARROWS, POINTS, NUMBERS)
    private val SECTION_COLOR = rgb(239, 108, 0)

    private fun setSource(style: Style, id: String, features: List<Feature>) {
        val collection = FeatureCollection.fromFeatures(features)
        style.getSourceAs<GeoJsonSource>(id)?.setGeoJson(collection) ?: style.addSource(GeoJsonSource(id, collection))
    }

    private fun addLayers(style: Style) {
        PlaceLayers.addBelow(
            style,
            LineLayer(LINE + CASING, LINE).withProperties(
                lineColor(rgb(255, 255, 255)),
                lineWidth(10f),
                lineCap(Property.LINE_CAP_ROUND),
                lineJoin(Property.LINE_JOIN_ROUND),
            ),
        )
        PlaceLayers.addBelow(
            style,
            LineLayer(LINE, LINE).withProperties(
                lineColor(COLOR),
                lineWidth(5.5f),
                lineCap(Property.LINE_CAP_ROUND),
                lineJoin(Property.LINE_JOIN_ROUND),
            ).withFilter(eq(get("t"), true)),
        )
        PlaceLayers.addBelow(
            style,
            LineLayer(LINE + STRAIGHT, LINE).withProperties(
                lineColor(COLOR),
                lineWidth(5.5f),
                lineDasharray(arrayOf(1.5f, 1.2f)),
            ).withFilter(eq(get("t"), false)),
        )
        TrackLayers.addArrowImage(style)
        PlaceLayers.addBelow(
            style,
            SymbolLayer(ARROWS, LINE).withProperties(
                symbolPlacement(Property.SYMBOL_PLACEMENT_LINE),
                symbolSpacing(TrackLayers.ARROW_SPACING),
                iconImage(TrackLayers.ARROW_IMAGE),
            ).apply { minZoom = TrackLayers.ARROWS_MIN_ZOOM },
        )
        PlaceLayers.addBelow(
            style,
            CircleLayer(POINTS, POINTS).withProperties(
                // Small, so they hide little of the map: a touch reaches further than the dot (PlanGestures).
                circleRadius(match(get("r"), literal(6f), stop("selected", 10f), stop("section", 10f), stop("mid", 4f))),
                circleColor(
                    match(
                        get("r"), rgb(255, 255, 255),
                        stop("start", rgb(46, 125, 50)),
                        stop("end", rgb(198, 40, 40)),
                        stop("selected", rgb(216, 27, 96)),
                        stop("section", SECTION_COLOR),
                    ),
                ),
                circleOpacity(match(get("r"), literal(1f), stop("mid", 0.85f))),
                circleStrokeColor(
                    match(
                        get("r"), rgb(216, 27, 96),
                        stop("start", rgb(255, 255, 255)),
                        stop("end", rgb(255, 255, 255)),
                        stop("selected", rgb(255, 255, 255)),
                        stop("section", rgb(255, 255, 255)),
                    ),
                ),
                circleStrokeWidth(match(get("r"), literal(2.5f), stop("mid", 1.5f))),
            ),
        )
        // The section's number in its end point (the selected one too: the number stays).
        PlaceLayers.addBelow(
            style,
            SymbolLayer(NUMBERS, POINTS).withProperties(
                textField(get("n")),
                textFont(arrayOf("Noto Sans Bold")),
                textSize(11f),
                textColor(rgb(255, 255, 255)),
                textAllowOverlap(true),
                textIgnorePlacement(true),
            ).withFilter(has("n")),
        )
    }

    private const val WAYS = "plan-ways"
    private const val SHOWN = "-shown"
    private const val WAY_NUMBERS = "plan-way-numbers"
    private const val AVOIDED = "plan-avoided"

    /** Each way's colour, in order, the same whether it's looked at or not: the first is the plan's own. */
    val WAY_COLORS = listOf(COLOR, 0xFF0277BD.toInt(), 0xFF2E7D32.toInt())

    private fun wayColor() = match(
        get("i"), rgb(117, 117, 117),
        *WAY_COLORS.mapIndexed { i, c -> stop(i, rgb((c shr 16) and 0xFF, (c shr 8) and 0xFF, c and 0xFF)) }.toTypedArray(),
    )

    /**
     * What the planner shows besides the plan: the [ways] offered for a leg, each in its own
     * colour, the [shown] one bold on top and the others thinner, with a number on each where
     * it runs apart from the others; and the [avoided] spots, a red disc with a cross. Empty
     * lists hide them.
     */
    fun syncExtras(style: Style, ways: List<List<LatLon>>, shown: Int?, avoided: List<LatLon>) {
        val lines = ways.mapIndexed { i, way ->
            Feature.fromGeometry(LineString.fromLngLats(way.map { Point.fromLngLat(it.lon, it.lat) })).apply {
                addNumberProperty("i", i)
                addBooleanProperty("s", i == shown)
            }
        }
        val marks = ways.mapIndexed { i, way ->
            val p = apart(way, ways.filterIndexed { j, _ -> j != i })
            Feature.fromGeometry(Point.fromLngLat(p.lon, p.lat)).apply {
                addNumberProperty("i", i)
                addStringProperty("n", "${i + 1}")
            }
        } + avoided.map { p ->
            Feature.fromGeometry(Point.fromLngLat(p.lon, p.lat)).apply { addStringProperty("x", "\u00d7") }
        }
        setSource(style, WAYS, lines)
        setSource(style, AVOIDED, marks)
        if (style.getLayer(WAYS) == null) addExtraLayers(style)
    }

    private val EXTRA_LAYERS = listOf(WAYS, WAYS + SHOWN + CASING, WAYS + SHOWN, WAY_NUMBERS, AVOIDED, AVOIDED + "-text")

    private fun addExtraLayers(style: Style) {
        // Over the plan: the others thinner, the one looked at bold, with an edge, on top.
        PlaceLayers.addBelow(
            style,
            LineLayer(WAYS, WAYS).withProperties(
                lineColor(wayColor()),
                lineWidth(4f),
                lineOpacity(0.85f),
                lineCap(Property.LINE_CAP_ROUND),
                lineJoin(Property.LINE_JOIN_ROUND),
            ).withFilter(eq(get("s"), false)),
        )
        PlaceLayers.addBelow(
            style,
            LineLayer(WAYS + SHOWN + CASING, WAYS).withProperties(
                lineColor(rgb(255, 255, 255)),
                lineWidth(10f),
                lineCap(Property.LINE_CAP_ROUND),
                lineJoin(Property.LINE_JOIN_ROUND),
            ).withFilter(eq(get("s"), true)),
        )
        PlaceLayers.addBelow(
            style,
            LineLayer(WAYS + SHOWN, WAYS).withProperties(
                lineColor(wayColor()),
                lineWidth(6f),
                lineCap(Property.LINE_CAP_ROUND),
                lineJoin(Property.LINE_JOIN_ROUND),
            ).withFilter(eq(get("s"), true)),
        )
        PlaceLayers.addBelow(
            style,
            CircleLayer(WAY_NUMBERS, AVOIDED).withProperties(
                circleRadius(12f),
                circleColor(wayColor()),
                circleStrokeColor(rgb(255, 255, 255)),
                circleStrokeWidth(2.5f),
            ).withFilter(has("n")),
        )
        PlaceLayers.addBelow(
            style,
            CircleLayer(AVOIDED, AVOIDED).withProperties(
                circleRadius(11f),
                circleColor(rgb(211, 47, 47)),
                circleStrokeColor(rgb(255, 255, 255)),
                circleStrokeWidth(2.5f),
            ).withFilter(has("x")),
        )
        PlaceLayers.addBelow(
            style,
            SymbolLayer(AVOIDED + "-text", AVOIDED).withProperties(
                textField(coalesce(get("n"), get("x"))),
                textFont(arrayOf("Noto Sans Bold")),
                textSize(15f),
                textColor(rgb(255, 255, 255)),
                textAllowOverlap(true),
                textIgnorePlacement(true),
            ),
        )
    }

    /** The point of [way] farthest from all of [others]: where its number can't be mistaken. */
    private fun apart(way: List<LatLon>, others: List<List<LatLon>>): LatLon {
        if (others.isEmpty() || way.size < 3) return PlanGeometry.halfway(way)
        val dense = PlanGeometry.densify(way, 50.0)
        val theirs = others.flatMap { PlanGeometry.densify(it, 50.0) }
        return dense.maxBy { p -> theirs.minOf { q -> app.sentrek.geo.Geo.distance(p, q) } }
    }
}
