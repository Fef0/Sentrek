package app.sentrek.map

import app.sentrek.geo.LatLon
import app.sentrek.geo.gpx.GpxWaypoint
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression.coalesce
import org.maplibre.android.style.expressions.Expression.get
import org.maplibre.android.style.expressions.Expression.literal
import org.maplibre.android.style.expressions.Expression.rgb
import org.maplibre.android.style.expressions.Expression.toColor
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
import org.maplibre.android.style.layers.PropertyFactory.textAnchor
import org.maplibre.android.style.layers.PropertyFactory.textColor
import org.maplibre.android.style.layers.PropertyFactory.textField
import org.maplibre.android.style.layers.PropertyFactory.textFont
import org.maplibre.android.style.layers.PropertyFactory.textHaloColor
import org.maplibre.android.style.layers.PropertyFactory.textHaloWidth
import org.maplibre.android.style.layers.PropertyFactory.textOffset
import org.maplibre.android.style.layers.PropertyFactory.textOptional
import org.maplibre.android.style.layers.PropertyFactory.textSize
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.MultiLineString
import org.maplibre.geojson.Point

/** A saved track drawn on the map. [color] is ARGB. */
data class MapTrack(
    val id: String,
    val color: Int,
    val segments: List<List<LatLon>>,
    val waypoints: List<GpxWaypoint>,
)

/**
 * Tracks drawn on top of the map: saved tracks the user chose to show, each in its own
 * colour and with its waypoints, and the one being recorded (signal orange, on top). Lines
 * have a white casing so they stand out from the trail colours. Hiking point icons stay above.
 */
object TrackLayers {
    /** Layer with the shown tracks' lines; features carry the track id as "id". */
    internal const val SHOWN = "track-shown"

    /** Layer with the shown tracks' waypoints; features carry "name" and the track id as "track". */
    internal const val WAYPOINTS = "track-waypoints"

    private const val RECORDING = "track-recording"
    private const val CASING = "-casing"
    private const val LABELS = "-labels"
    private const val COLOR = "c"
    private const val RECORDING_COLOR = 0xFFE85D04.toInt()

    /** The tracks shown on the map and their waypoints: changes only when the user changes them. */
    fun syncShown(style: Style, shown: List<MapTrack>) {
        setLines(style, SHOWN, shown.map { track -> lineFeature(track.segments, track.color).apply { addStringProperty("id", track.id) } })
        setWaypoints(
            style,
            shown.flatMap { track ->
                track.waypoints.map { w ->
                    Feature.fromGeometry(Point.fromLngLat(w.position.lon, w.position.lat)).apply {
                        w.name?.let { addStringProperty("name", it) }
                        addStringProperty("class", "waypoint")
                        addStringProperty("track", track.id)
                        addStringProperty(COLOR, hex(track.color))
                    }
                }
            },
        )
    }

    /**
     * The track being recorded, on its own: it grows while the user walks, and the shown
     * tracks needn't be sent to the map again with it.
     */
    fun syncRecording(style: Style, recording: List<List<LatLon>>) {
        // Above the shown tracks: their layers come first.
        if (style.getSource(SHOWN) == null) syncShown(style, emptyList())
        setLines(style, RECORDING, listOf(lineFeature(recording, RECORDING_COLOR)))
    }

    private fun lineFeature(segments: List<List<LatLon>>, color: Int): Feature {
        val lines = segments.filter { it.size >= 2 }.map { s -> s.map { Point.fromLngLat(it.lon, it.lat) } }
        return Feature.fromGeometry(MultiLineString.fromLngLats(lines)).apply { addStringProperty(COLOR, hex(color)) }
    }

    private fun setLines(style: Style, id: String, features: List<Feature>) {
        val collection = FeatureCollection.fromFeatures(features)
        val source = style.getSourceAs<GeoJsonSource>(id)
        if (source != null) {
            source.setGeoJson(collection)
            return
        }
        style.addSource(GeoJsonSource(id, collection))
        PlaceLayers.addBelow(
            style,
            LineLayer(id + CASING, id).withProperties(
                lineColor(rgb(255, 255, 255)),
                lineWidth(8f),
                lineCap(Property.LINE_CAP_ROUND),
                lineJoin(Property.LINE_JOIN_ROUND),
            ),
        )
        PlaceLayers.addBelow(
            style,
            LineLayer(id, id).withProperties(
                lineColor(toColor(get(COLOR))),
                lineWidth(4.5f),
                lineCap(Property.LINE_CAP_ROUND),
                lineJoin(Property.LINE_JOIN_ROUND),
            ),
        )
    }

    private fun setWaypoints(style: Style, features: List<Feature>) {
        val collection = FeatureCollection.fromFeatures(features)
        val source = style.getSourceAs<GeoJsonSource>(WAYPOINTS)
        if (source != null) {
            source.setGeoJson(collection)
            return
        }
        style.addSource(GeoJsonSource(WAYPOINTS, collection))
        PlaceLayers.addBelow(
            style,
            CircleLayer(WAYPOINTS, WAYPOINTS).withProperties(
                circleRadius(7f),
                circleColor(toColor(get(COLOR))),
                circleStrokeColor(rgb(255, 255, 255)),
                circleStrokeWidth(2.5f),
            ),
        )
        PlaceLayers.addBelow(
            style,
            SymbolLayer(WAYPOINTS + LABELS, WAYPOINTS).withProperties(
                textField(coalesce(get("name"), literal(""))),
                textFont(arrayOf("Noto Sans Bold")),
                textSize(12f),
                textAnchor(Property.TEXT_ANCHOR_TOP),
                textOffset(arrayOf(0f, 0.9f)),
                textOptional(true),
                textColor(toColor(get(COLOR))),
                textHaloColor(rgb(255, 255, 255)),
                textHaloWidth(1.8f),
            ),
        )
    }

    private fun hex(argb: Int) = String.format("#%06X", argb and 0xFFFFFF)
}
