package app.sentrek.map

import org.maplibre.android.style.layers.PropertyFactory.textIgnorePlacement
import org.maplibre.android.style.layers.PropertyFactory.textAllowOverlap
import org.maplibre.android.style.layers.PropertyFactory.textColor
import org.maplibre.android.style.layers.PropertyFactory.textSize
import org.maplibre.android.style.layers.PropertyFactory.textFont
import org.maplibre.android.style.layers.PropertyFactory.textField
import org.maplibre.android.style.expressions.Expression.has
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
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
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

    /** [sectionEnds]: the indices of the points that end a section, numbered on the map. */
    fun sync(style: Style, points: List<LatLon>, legs: List<PlanLeg>, selected: Int?, sectionEnds: List<Int> = emptyList()) {
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
    }

    /** Hides the plan without forgetting the layers (the planner was closed). */
    fun clear(style: Style) = sync(style, emptyList(), emptyList(), null)

    private const val NUMBERS = "plan-section-numbers"
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
                lineWidth(8f),
                lineCap(Property.LINE_CAP_ROUND),
                lineJoin(Property.LINE_JOIN_ROUND),
            ),
        )
        PlaceLayers.addBelow(
            style,
            LineLayer(LINE, LINE).withProperties(
                lineColor(COLOR),
                lineWidth(4.5f),
                lineCap(Property.LINE_CAP_ROUND),
                lineJoin(Property.LINE_JOIN_ROUND),
            ).withFilter(eq(get("t"), true)),
        )
        PlaceLayers.addBelow(
            style,
            LineLayer(LINE + STRAIGHT, LINE).withProperties(
                lineColor(COLOR),
                lineWidth(4.5f),
                lineDasharray(arrayOf(1.5f, 1.2f)),
            ).withFilter(eq(get("t"), false)),
        )
        PlaceLayers.addBelow(
            style,
            CircleLayer(POINTS, POINTS).withProperties(
                circleRadius(match(get("r"), literal(8f), stop("selected", 12f), stop("section", 12f), stop("mid", 5f))),
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
                circleStrokeWidth(match(get("r"), literal(3f), stop("mid", 2f))),
            ),
        )
        // The section's number in its end point (the selected one too: the number stays).
        PlaceLayers.addBelow(
            style,
            SymbolLayer(NUMBERS, POINTS).withProperties(
                textField(get("n")),
                textFont(arrayOf("Noto Sans Bold")),
                textSize(12f),
                textColor(rgb(255, 255, 255)),
                textAllowOverlap(true),
                textIgnorePlacement(true),
            ).withFilter(has("n")),
        )
    }
}
