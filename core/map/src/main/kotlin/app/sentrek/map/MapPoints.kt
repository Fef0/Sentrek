package app.sentrek.map

import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory.visibility

/**
 * The points of the map data: huts, springs, peaks, viewpoints, fords and the rest, from the
 * base style and from the downloaded areas. Hidden, only the map itself is left; the user's
 * own places and the waypoints of shown tracks stay.
 */
object MapPoints {
    /** The base style's point layers (hiking.json). */
    private val BASE = listOf("poi-hiking", "poi-landmarks", "poi-detail", "peak", "path-ford")

    /** Call again after [MapOverlays.sync]: it makes the areas' point layers anew. */
    fun show(style: Style, show: Boolean) {
        val value = visibility(if (show) Property.VISIBLE else Property.NONE)
        for (layer in style.layers) {
            if (layer.id in BASE || MapOverlays.isPointLayer(layer.id)) layer.setProperties(value)
        }
    }
}
