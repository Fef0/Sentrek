package app.sentrek.map

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import app.sentrek.geo.Geo
import app.sentrek.geo.LatLon
import app.sentrek.geo.gpx.GpxWaypoint
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression.coalesce
import org.maplibre.android.style.expressions.Expression.get
import org.maplibre.android.style.expressions.Expression.literal
import org.maplibre.android.style.expressions.Expression.match
import org.maplibre.android.style.expressions.Expression.stop
import org.maplibre.android.style.expressions.Expression.rgb
import org.maplibre.android.style.expressions.Expression.toColor
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory.circleColor
import org.maplibre.android.style.layers.PropertyFactory.circleRadius
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth
import org.maplibre.android.style.layers.PropertyFactory.iconImage
import org.maplibre.android.style.layers.PropertyFactory.symbolPlacement
import org.maplibre.android.style.layers.PropertyFactory.symbolSpacing
import org.maplibre.android.style.layers.PropertyFactory.visibility
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
import org.maplibre.android.style.sources.GeoJsonOptions
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

    /** Layer with where each shown track starts and ends; features carry "r" (start, end, both) and "label". */
    private const val ENDS = "track-ends"

    /** Small arrows along the shown tracks, the way they go: on a loop, the start doesn't say. */
    private const val ARROWS = "track-arrows"
    private const val ARROW_IMAGE = "track-arrow"

    /** Closer out, a track is a few pixels long and the arrows would only be noise. */
    private const val ARROWS_MIN_ZOOM = 12f

    /** Screen pixels between arrows: a few on screen at any zoom. */
    private const val ARROW_SPACING = 180f

    /** Start and end closer than this (a loop): one marker for both, or the labels would collide. */
    private const val SAME_PLACE_M = 30.0

    private const val RECORDING = "track-recording"

    /** The newest stretch of the recording: the only part sent again as it grows. */
    private const val RECORDING_TAIL = "track-recording-tail"

    /** Points in the tail before it joins the rest, which is then sent once. */
    private const val TAIL_POINTS = 200
    private const val CASING = "-casing"
    private const val LABELS = "-labels"
    private const val COLOR = "c"
    private const val RECORDING_COLOR = 0xFFE85D04.toInt()

    /**
     * The tracks shown on the map, their waypoints, and where each starts and ends ([start],
     * [end]: the words for the markers, so the direction can be told): changes only when the
     * user changes them. [arrows]: small arrows along the lines, the way they go.
     */
    fun syncShown(style: Style, shown: List<MapTrack>, start: String = "", end: String = "", arrows: Boolean = true) {
        setLines(style, SHOWN, shown.map { track -> lineFeature(track.segments, track.color).apply { addStringProperty("id", track.id) } })
        // Where tracks share a trail, only the top one's arrows: the others' would point every way.
        val arrowLines = if (arrows) {
            ArrowParts.free(shown.map { it.segments }).filter { it.isNotEmpty() }.map { lines ->
                Feature.fromGeometry(MultiLineString.fromLngLats(lines.map { l -> l.map { Point.fromLngLat(it.lon, it.lat) } }))
            }
        } else {
            emptyList()
        }
        val arrowSource = style.getSourceAs<GeoJsonSource>(ARROWS)
        if (arrowSource != null) {
            arrowSource.setGeoJson(FeatureCollection.fromFeatures(arrowLines))
        } else {
            style.addSource(GeoJsonSource(ARROWS, FeatureCollection.fromFeatures(arrowLines), lineOptions()))
        }
        if (style.getLayer(ARROWS) == null) addArrows(style)
        style.getLayer(ARROWS)?.setProperties(visibility(if (arrows) Property.VISIBLE else Property.NONE))
        setEnds(style, shown.flatMap { ends(it, start, end) })
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
        if (style.getSource(RECORDING) == null) addRecordingLayers(style)
        val total = recording.sumOf { it.size }
        // A long walk is thousands of points: most of it is sent once, and only the last
        // stretch every few seconds.
        if (style !== frozenStyle || total < frozenPoints || total - frozenPoints > TAIL_POINTS) {
            frozenStyle = style
            frozenPoints = total
            style.getSourceAs<GeoJsonSource>(RECORDING)
                ?.setGeoJson(FeatureCollection.fromFeatures(listOf(lineFeature(recording, RECORDING_COLOR))))
        }
        style.getSourceAs<GeoJsonSource>(RECORDING_TAIL)
            ?.setGeoJson(FeatureCollection.fromFeatures(listOf(lineFeature(tail(recording, frozenPoints), RECORDING_COLOR))))
    }

    private var frozenStyle: Style? = null
    private var frozenPoints = 0

    /** The points from index [from] on (counted across segments), with the one before to join the line. */
    private fun tail(segments: List<List<LatLon>>, from: Int): List<List<LatLon>> {
        var start = 0
        val out = ArrayList<List<LatLon>>()
        for (s in segments) {
            val end = start + s.size
            if (end > from) out += s.subList(maxOf(0, from - start - 1), s.size)
            start = end
        }
        return out
    }

    /** Both casings below both lines, so the join of the two parts doesn't show. */
    private fun addRecordingLayers(style: Style) {
        for (id in listOf(RECORDING, RECORDING_TAIL)) style.addSource(GeoJsonSource(id, FeatureCollection.fromFeatures(emptyList()), lineOptions()))
        for (id in listOf(RECORDING, RECORDING_TAIL)) PlaceLayers.addBelow(style, casing(id))
        for (id in listOf(RECORDING, RECORDING_TAIL)) PlaceLayers.addBelow(style, line(id))
    }

    /**
     * Lines needn't be cut finer than this zoom: closer in, the tiles of zoom 16 are enlarged.
     * Fewer tiles to cut each time a line changes.
     */
    private fun lineOptions() = GeoJsonOptions().withMaxZoom(16)

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
        style.addSource(GeoJsonSource(id, collection, lineOptions()))
        PlaceLayers.addBelow(style, casing(id))
        PlaceLayers.addBelow(style, line(id))
    }

    private fun casing(id: String) = LineLayer(id + CASING, id).withProperties(
        lineColor(rgb(255, 255, 255)),
        lineWidth(8f),
        lineCap(Property.LINE_CAP_ROUND),
        lineJoin(Property.LINE_JOIN_ROUND),
    )

    private fun line(id: String) = LineLayer(id, id).withProperties(
        lineColor(toColor(get(COLOR))),
        lineWidth(4.5f),
        lineCap(Property.LINE_CAP_ROUND),
        lineJoin(Property.LINE_JOIN_ROUND),
    )

    /**
     * White arrows inside the lines, turned with them. Below the labels and icons, which win
     * the space: an arrow that would cover one is left out, the next one shows.
     */
    private fun addArrows(style: Style) {
        if (style.getImage(ARROW_IMAGE) == null) style.addImage(ARROW_IMAGE, arrow())
        PlaceLayers.addBelow(
            style,
            SymbolLayer(ARROWS, ARROWS).withProperties(
                symbolPlacement(Property.SYMBOL_PLACEMENT_LINE),
                symbolSpacing(ARROW_SPACING),
                iconImage(ARROW_IMAGE),
            ).apply { minZoom = ARROWS_MIN_ZOOM },
        )
    }

    /**
     * A chevron pointing along the line (right, as MapLibre lays icons on lines): white with a
     * dark edge, a little wider than the line, so it reads on any track colour and on its casing.
     */
    private fun arrow(): Bitmap {
        val density = Resources.getSystem().displayMetrics.density
        val size = (14 * density).toInt().coerceAtLeast(14)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val path = Path().apply {
            moveTo(size * 0.32f, size * 0.22f)
            lineTo(size * 0.68f, size * 0.5f)
            lineTo(size * 0.32f, size * 0.78f)
        }
        val canvas = Canvas(bitmap)
        for ((color, width) in listOf(0x99000000.toInt() to 4.4f, android.graphics.Color.WHITE to 2.4f)) {
            canvas.drawPath(
                path,
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    this.color = color
                    style = Paint.Style.STROKE
                    strokeWidth = width * density
                    strokeCap = Paint.Cap.ROUND
                    strokeJoin = Paint.Join.ROUND
                },
            )
        }
        return bitmap
    }

    private fun ends(track: MapTrack, start: String, end: String): List<Feature> {
        val first = track.segments.firstOrNull { it.isNotEmpty() }?.first() ?: return emptyList()
        val last = track.segments.lastOrNull { it.isNotEmpty() }?.last() ?: return emptyList()
        fun marker(p: LatLon, role: String, label: String) = Feature.fromGeometry(Point.fromLngLat(p.lon, p.lat)).apply {
            addStringProperty("r", role)
            addStringProperty("label", label)
        }
        if (Geo.distance(first, last) < SAME_PLACE_M) return listOf(marker(first, "both", "$start · $end"))
        return listOf(marker(first, "start", start), marker(last, "end", end))
    }

    private fun setEnds(style: Style, features: List<Feature>) {
        val collection = FeatureCollection.fromFeatures(features)
        val source = style.getSourceAs<GeoJsonSource>(ENDS)
        if (source != null) {
            source.setGeoJson(collection)
            return
        }
        style.addSource(GeoJsonSource(ENDS, collection))
        // The planner's colours: green where it starts, red where it ends.
        val color = match(get("r"), rgb(198, 40, 40), stop("start", rgb(46, 125, 50)), stop("both", rgb(46, 125, 50)))
        PlaceLayers.addBelow(
            style,
            CircleLayer(ENDS, ENDS).withProperties(
                circleRadius(7f),
                circleColor(color),
                circleStrokeColor(rgb(255, 255, 255)),
                circleStrokeWidth(2.5f),
            ),
        )
        PlaceLayers.addBelow(
            style,
            SymbolLayer(ENDS + LABELS, ENDS).withProperties(
                textField(get("label")),
                textFont(arrayOf("Noto Sans Bold")),
                textSize(12f),
                textAnchor(Property.TEXT_ANCHOR_TOP),
                textOffset(arrayOf(0f, 0.9f)),
                textOptional(true),
                textColor(color),
                textHaloColor(rgb(255, 255, 255)),
                textHaloWidth(1.8f),
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
