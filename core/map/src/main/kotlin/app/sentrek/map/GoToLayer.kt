package app.sentrek.map

import app.sentrek.geo.Geo
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
import org.maplibre.android.style.layers.PropertyFactory.lineDasharray
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

/**
 * "Take me there": a dashed straight line from the user to the place, and a ring on the place.
 * Straight on purpose: it says where the place is, not which way to walk.
 */
object GoToLayer {
    private const val LINE = "goto-line"
    private const val TARGET = "goto-target"
    private const val COLOR = 0xFFE85D04.toInt()
    private const val MIN_MOVE_M = 5.0

    /** What the map has now, so that an unchanged line isn't sent again. */
    private var sentStyle: Style? = null
    private var sentFrom: LatLon? = null
    private var sentTo: LatLon? = null

    /**
     * Each change makes the map lay out its labels again and fade them in: a dozen frames.
     * Called with every fix, so only a line that really moved (a few metres) is sent.
     */
    fun sync(style: Style, from: LatLon?, to: LatLon?) {
        val same = style === sentStyle && to == sentTo &&
            (from == sentFrom || (from != null && sentFrom != null && Geo.distance(from, sentFrom!!) < MIN_MOVE_M))
        if (same) return
        sentStyle = style
        sentFrom = from
        sentTo = to
        val line = if (from != null && to != null) {
            listOf(Feature.fromGeometry(LineString.fromLngLats(listOf(point(from), point(to)))))
        } else {
            emptyList()
        }
        set(style, LINE, line) {
            LineLayer(LINE, LINE).withProperties(
                lineColor(COLOR),
                lineWidth(3.5f),
                lineDasharray(arrayOf(1.5f, 1.5f)),
                lineCap(Property.LINE_CAP_ROUND),
            )
        }
        set(style, TARGET, listOfNotNull(to?.let { Feature.fromGeometry(point(it)) })) {
            CircleLayer(TARGET, TARGET).withProperties(
                circleRadius(11f),
                circleColor(rgb(255, 255, 255)),
                circleStrokeColor(COLOR),
                circleStrokeWidth(4f),
            )
        }
    }

    private fun set(style: Style, id: String, features: List<Feature>, layer: () -> org.maplibre.android.style.layers.Layer) {
        val collection = FeatureCollection.fromFeatures(features)
        val source = style.getSourceAs<GeoJsonSource>(id)
        if (source != null) {
            source.setGeoJson(collection)
            return
        }
        style.addSource(GeoJsonSource(id, collection))
        PlaceLayers.addBelow(style, layer())
    }

    private fun point(p: LatLon) = Point.fromLngLat(p.lon, p.lat)
}
