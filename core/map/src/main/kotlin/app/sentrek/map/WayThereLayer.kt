package app.sentrek.map

import app.sentrek.geo.LatLon
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression.rgb
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory.circleColor
import org.maplibre.android.style.layers.PropertyFactory.circleRadius
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth
import org.maplibre.android.style.layers.PropertyFactory.lineCap
import org.maplibre.android.style.layers.PropertyFactory.lineColor
import org.maplibre.android.style.layers.PropertyFactory.lineJoin
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

/**
 * The way along the paths to a searched place, before the user chooses to follow it: a line
 * in the colour of the way to a point (GoToLayer), solid, as it follows the paths, and a ring
 * on the place.
 */
object WayThereLayer {
    private const val LINE = "way-there-line"
    private const val TARGET = "way-there-target"
    private var sentStyle: Style? = null
    private var sent: List<LatLon>? = null

    /** [points]: the way, from the user to the place; empty: nothing shown. */
    fun sync(style: Style, points: List<LatLon>) {
        if (style === sentStyle && points == sent) return
        sentStyle = style
        sent = points
        val line = if (points.size >= 2) {
            listOf(Feature.fromGeometry(LineString.fromLngLats(points.map(::point))))
        } else {
            emptyList()
        }
        set(style, LINE, line, MapOrder::addLine) {
            listOf(
                LineLayer(LINE + CASING, LINE).withProperties(
                    lineColor(rgb(255, 255, 255)),
                    lineWidth(8f),
                    lineCap(Property.LINE_CAP_ROUND),
                    lineJoin(Property.LINE_JOIN_ROUND),
                ),
                LineLayer(LINE, LINE).withProperties(
                    lineColor(GoToLayer.COLOR),
                    lineWidth(4.5f),
                    lineCap(Property.LINE_CAP_ROUND),
                    lineJoin(Property.LINE_JOIN_ROUND),
                ),
            )
        }
        set(style, TARGET, listOfNotNull(points.lastOrNull()?.takeIf { points.size >= 2 }?.let { Feature.fromGeometry(point(it)) }), MapOrder::addPoint) {
            listOf(
                CircleLayer(TARGET, TARGET).withProperties(
                    circleRadius(11f),
                    circleColor(rgb(255, 255, 255)),
                    circleStrokeColor(GoToLayer.COLOR),
                    circleStrokeWidth(4f),
                ),
            )
        }
    }

    private fun set(
        style: Style,
        id: String,
        features: List<Feature>,
        add: (Style, org.maplibre.android.style.layers.Layer) -> Unit,
        layers: () -> List<org.maplibre.android.style.layers.Layer>,
    ) {
        val collection = FeatureCollection.fromFeatures(features)
        val source = style.getSourceAs<GeoJsonSource>(id)
        if (source != null) {
            source.setGeoJson(collection)
            return
        }
        style.addSource(GeoJsonSource(id, collection))
        layers().forEach { add(style, it) }
    }

    private fun point(p: LatLon) = Point.fromLngLat(p.lon, p.lat)

    private const val CASING = "-casing"
}
