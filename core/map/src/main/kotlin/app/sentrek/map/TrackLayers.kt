package app.sentrek.map

import app.sentrek.geo.LatLon
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.expressions.Expression.rgb
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory.lineCap
import org.maplibre.android.style.layers.PropertyFactory.lineColor
import org.maplibre.android.style.layers.PropertyFactory.lineJoin
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.MultiLineString
import org.maplibre.geojson.Point

/**
 * Tracks drawn on top of the map: saved tracks the user chose to show (purple), and the one
 * being recorded (signal orange, on top). Both have a white casing so they stand out from
 * the red and blue trail colours. Hiking point icons stay above them.
 */
object TrackLayers {
    private const val SHOWN = "track-shown"
    private const val RECORDING = "track-recording"
    private const val CASING = "-casing"

    fun sync(style: Style, recording: List<List<LatLon>>, shown: Collection<List<List<LatLon>>>) {
        set(style, SHOWN, shown.flatten(), rgb(123, 31, 162))
        set(style, RECORDING, recording, rgb(232, 93, 4))
    }

    private fun set(style: Style, id: String, segments: List<List<LatLon>>, color: Expression) {
        val lines = segments.filter { it.size >= 2 }.map { s -> s.map { Point.fromLngLat(it.lon, it.lat) } }
        val feature = Feature.fromGeometry(MultiLineString.fromLngLats(lines))
        val source = style.getSourceAs<GeoJsonSource>(id)
        if (source != null) {
            source.setGeoJson(feature)
            return
        }
        style.addSource(GeoJsonSource(id, feature))
        style.addLayer(
            LineLayer(id + CASING, id).withProperties(
                lineColor(rgb(255, 255, 255)),
                lineWidth(8f),
                lineCap(Property.LINE_CAP_ROUND),
                lineJoin(Property.LINE_JOIN_ROUND),
            ),
        )
        style.addLayer(
            LineLayer(id, id).withProperties(
                lineColor(color),
                lineWidth(4.5f),
                lineCap(Property.LINE_CAP_ROUND),
                lineJoin(Property.LINE_JOIN_ROUND),
            ),
        )
    }
}
