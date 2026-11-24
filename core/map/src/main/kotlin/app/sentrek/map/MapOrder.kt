package app.sentrek.map

import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.BackgroundLayer
import org.maplibre.android.style.layers.Layer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory.visibility
import org.maplibre.android.style.layers.SymbolLayer

/**
 * What draws over what, whatever order things are added in. Bottom to top:
 *
 * 1. the base map's ground (land, water, roads), then the downloaded trails, routes and ferratas;
 * 2. the lines of the app: tracks, the plan, the way to a point;
 * 3. the base map's names and icons;
 * 4. points: the map's huts and springs, track waypoints and ends, the plan's points;
 * 5. saved places and the searched spot;
 * 6. the last place with signal;
 * 7. the position marker, above everything.
 *
 * Each level ends at an empty, hidden layer; a layer is put just under the end of its level.
 */
object MapOrder {
    /** Ends level 1: the downloaded trails and the rest of the ground go under it. */
    const val TRAILS = "order-trails"
    private const val LINES = "order-lines"
    private const val POINTS = "order-points"
    private const val PLACES = "order-places"

    /** Ends level 6: the position marker goes right above it. */
    const val SIGNAL = "order-signal"

    /** Sets the levels up in a new style, before anything else is added to it. */
    fun install(style: Style) {
        if (style.getLayer(TRAILS) != null) return
        // Under the base map's first name or icon: lines then don't cover any.
        val firstLabel = style.layers.firstOrNull { it is SymbolLayer }?.id
        val trails = anchor(TRAILS)
        val lines = anchor(LINES)
        if (firstLabel != null) {
            style.addLayerBelow(trails, firstLabel)
            style.addLayerBelow(lines, firstLabel)
        } else {
            style.addLayer(trails)
            style.addLayer(lines)
        }
        for (id in listOf(POINTS, PLACES, SIGNAL)) style.addLayer(anchor(id))
    }

    /** A track, the plan, any line of the app's own. */
    fun addLine(style: Style, layer: Layer) = add(style, layer, LINES)

    /** A point (or its label) that stays over every line. */
    fun addPoint(style: Style, layer: Layer) = add(style, layer, POINTS)

    /** Saved places and the searched spot: over the other points. */
    fun addPlace(style: Style, layer: Layer) = add(style, layer, PLACES)

    /** The last place with signal: over every point, under the position only. */
    fun addSignal(style: Style, layer: Layer) = add(style, layer, SIGNAL)

    /** Layers added so far: a layer that must stay on top of its level can tell when another came. */
    var added = 0
        private set

    /** Where the app's lines end in [style]'s layers: the index of their end marker, -1 before [install]. */
    internal fun linesEnd(style: Style): Int = style.layers.indexOfFirst { it.id == LINES }

    private fun add(style: Style, layer: Layer, below: String) {
        install(style)
        added++
        style.addLayerBelow(layer, below)
    }

    private fun anchor(id: String) = BackgroundLayer(id).withProperties(visibility(Property.NONE))
}
