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
import org.maplibre.android.style.layers.RasterLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.android.style.sources.RasterSource
import java.io.File
import java.net.URI

/** A raster map file imported by the user, e.g. `mbtiles:///…` or `pmtiles://file:///…`. */
data class RasterOverlay(val id: String, val url: String)

/**
 * Layers Sentrek adds on top of the base style, always in this order (bottom to top),
 * and all below the base labels so names stay readable:
 * 1. imported raster maps,
 * 2. trail difficulty, one GeoJSON file per downloaded cell. Colours follow Swiss waymarking:
 *    T1 yellow, T2–T3 red-white-red, T4–T6 blue-white-blue. Faint trails get sparser dashes.
 */
object MapOverlays {
    private const val RASTER = "import-"
    private const val TRAILS = "trails-"
    private const val CLEAR = "-clear"
    private const val FAINT = "-faint"

    /** First label layer of the hiking style. */
    private const val BELOW_LAYER = "water-name"

    fun sync(style: Style, rasters: List<RasterOverlay>, trails: Map<String, File>) {
        // Layers are cheap to rebuild and that keeps the order right; sources (parsed
        // GeoJSON, open tile files) are kept whenever possible.
        style.layers.filter { it.id.startsWith(RASTER) || it.id.startsWith(TRAILS) }.forEach { style.removeLayer(it) }

        val wanted = rasters.map { RASTER + it.id }.toSet() + trails.keys.map { TRAILS + it }
        style.sources.filter { (it.id.startsWith(RASTER) || it.id.startsWith(TRAILS)) && it.id !in wanted }
            .forEach { style.removeSource(it) }

        for (raster in rasters) {
            val sourceId = RASTER + raster.id
            if (style.getSource(sourceId) == null) style.addSource(RasterSource(sourceId, raster.url, 256))
            style.addLayerBelow(RasterLayer(sourceId, sourceId), BELOW_LAYER)
        }
        for ((id, file) in trails) {
            val sourceId = TRAILS + id
            if (style.getSource(sourceId) == null) {
                // MapLibre needs "file://" + absolute path; File.toURI() yields "file:/…", which it rejects.
                style.addSource(GeoJsonSource(sourceId, URI("file://${file.absolutePath}")))
            }
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
