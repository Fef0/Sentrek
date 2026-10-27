package app.sentrek.map

import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression.get
import org.maplibre.android.style.expressions.Expression.gte
import org.maplibre.android.style.expressions.Expression.interpolate
import org.maplibre.android.style.expressions.Expression.linear
import org.maplibre.android.style.expressions.Expression.literal
import org.maplibre.android.style.expressions.Expression.lt
import org.maplibre.android.style.expressions.Expression.rgb
import org.maplibre.android.style.expressions.Expression.step
import org.maplibre.android.style.expressions.Expression.stop
import org.maplibre.android.style.expressions.Expression.zoom
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory.lineCap
import org.maplibre.android.style.layers.PropertyFactory.lineColor
import org.maplibre.android.style.layers.PropertyFactory.lineDasharray
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
import org.maplibre.android.style.sources.GeoJsonSource
import java.io.File
import java.net.URI

/**
 * Trail difficulty overlay: one GeoJSON file per downloaded region cell, drawn over the
 * base `path` layer. Colours follow Swiss waymarking: T1 yellow, T2–T3 red-white-red,
 * T4–T6 blue-white-blue. Faint trails get sparser dashes.
 */
object TrailOverlay {
    private const val PREFIX = "trails-"
    private const val CLEAR = "-clear"
    private const val FAINT = "-faint"

    /** First label layer of the hiking style: overlays go below it so names stay readable. */
    private const val BELOW_LAYER = "water-name"

    /** Makes the style show exactly the overlays in [files] (cell id → GeoJSON file). */
    fun sync(style: Style, files: Map<String, File>) {
        val present = style.sources.map { it.id }.filter { it.startsWith(PREFIX) }.map { it.removePrefix(PREFIX) }.toSet()
        for (id in present - files.keys) {
            style.removeLayer(PREFIX + id + CLEAR)
            style.removeLayer(PREFIX + id + FAINT)
            style.removeSource(PREFIX + id)
        }
        for ((id, file) in files) {
            if (id in present) continue
            val sourceId = PREFIX + id
            // MapLibre needs "file://" + absolute path; File.toURI() yields "file:/..." which it rejects.
            style.addSource(GeoJsonSource(sourceId, URI("file://${file.absolutePath}")))
            // Visibility 0 (unknown) to 3 (intermediate) is a trail you can follow.
            style.addLayerBelow(trailLayer(sourceId, sourceId + CLEAR, faint = false), BELOW_LAYER)
            style.addLayerBelow(trailLayer(sourceId, sourceId + FAINT, faint = true), BELOW_LAYER)
        }
    }

    private fun trailLayer(sourceId: String, layerId: String, faint: Boolean) =
        LineLayer(layerId, sourceId).apply {
            minZoom = 11f
            setFilter(if (faint) gte(get("v"), literal(4)) else lt(get("v"), literal(4)))
            setProperties(
                lineColor(
                    step(
                        get("s"), rgb(230, 175, 0),
                        stop(2, rgb(200, 30, 40)),
                        stop(4, rgb(30, 80, 200)),
                    ),
                ),
                lineWidth(interpolate(linear(), zoom(), stop(11, 1.5f), stop(14, 3f), stop(17, 5f))),
                lineDasharray(if (faint) arrayOf(1f, 2.5f) else arrayOf(3f, 1.5f)),
                lineCap(Property.LINE_CAP_BUTT),
            )
        }
}
