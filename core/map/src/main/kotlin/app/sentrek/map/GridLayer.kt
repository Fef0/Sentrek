package app.sentrek.map

import app.sentrek.geo.BoundingBox
import android.graphics.PointF
import app.sentrek.geo.CoordinateGrid
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression.get
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory.lineColor
import org.maplibre.android.style.layers.PropertyFactory.lineOpacity
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
import org.maplibre.android.style.layers.PropertyFactory.textAllowOverlap
import org.maplibre.android.style.layers.PropertyFactory.textColor
import org.maplibre.android.style.layers.PropertyFactory.textField
import org.maplibre.android.style.layers.PropertyFactory.textFont
import org.maplibre.android.style.layers.PropertyFactory.textHaloColor
import org.maplibre.android.style.layers.PropertyFactory.textHaloWidth
import org.maplibre.android.style.layers.PropertyFactory.textIgnorePlacement
import org.maplibre.android.style.layers.PropertyFactory.textSize
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

/**
 * The coordinate grid over the map ([CoordinateGrid]): thin lines under the labels, their
 * numbers along them. Made for a little more than the area on screen, again when the map
 * moves past it or zooms to another step.
 */
object GridLayer {
    private const val SOURCE = "coordinate-grid"
    private const val LABEL_SOURCE = "coordinate-grid-numbers"
    private const val LINES = "coordinate-grid-lines"
    private const val LABELS = "coordinate-grid-labels"
    private const val BELOW_LABELS = "water-name"

    /** The area and step the grid was last made for, per style. */
    private class Made(val kind: CoordinateGrid.Kind, val box: BoundingBox, val step: Int, val lines: List<CoordinateGrid.Line>)

    private val made = java.util.WeakHashMap<Style, Made>()

    /**
     * Draws [kind] for the camera of [map] now; [CoordinateGrid.Kind.Off] removes it. The
     * numbers go along the screen's top and left edges, as on the margins of a paper map, so
     * they're always in view: [topPx] and [leftPx] from those edges.
     */
    fun sync(style: Style, map: MapLibreMap, kind: CoordinateGrid.Kind, night: Boolean, topPx: Float, leftPx: Float) {
        if (kind == CoordinateGrid.Kind.Off) {
            remove(style)
            return
        }
        val bounds = map.projection.visibleRegion.latLngBounds
        val visible = BoundingBox(
            bounds.latitudeSouth.coerceIn(-85.0, 85.0),
            bounds.longitudeWest.coerceIn(-180.0, 180.0),
            bounds.latitudeNorth.coerceIn(-85.0, 85.0),
            bounds.longitudeEast.coerceIn(-180.0, 180.0),
        )
        if (visible.south >= visible.north || visible.west >= visible.east) return
        val lat = (visible.south + visible.north) / 2
        val metersPerPixel = map.projection.getMetersPerPixelAtLatitude(lat)
        val step = if (kind == CoordinateGrid.Kind.Utm) CoordinateGrid.utmStep(metersPerPixel) else CoordinateGrid.angleStepMinutes(metersPerPixel)
        val last = made[style]
        if (last != null && last.kind == kind && last.step == step && style.getSource(SOURCE) != null && inside(visible, last.box)) {
            numbers(style, map, last.lines, topPx, leftPx)
            ensureLayers(style, night)
            return
        }
        val box = grow(visible)
        val lines = if (kind == CoordinateGrid.Kind.Utm) CoordinateGrid.utm(box, step) else CoordinateGrid.latLon(box, step)
        val features = FeatureCollection.fromFeatures(
            lines.map { line ->
                Feature.fromGeometry(LineString.fromLngLats(line.points.map { Point.fromLngLat(it.lon, it.lat) }))
            },
        )
        style.getSourceAs<GeoJsonSource>(SOURCE)?.setGeoJson(features) ?: style.addSource(GeoJsonSource(SOURCE, features))
        made[style] = Made(kind, box, step, lines)
        numbers(style, map, lines, topPx, leftPx)
        ensureLayers(style, night)
    }

    /** Each line's number where it crosses the line [topPx] down the screen, or [leftPx] in from its left. */
    private fun numbers(style: Style, map: MapLibreMap, lines: List<CoordinateGrid.Line>, topPx: Float, leftPx: Float) {
        val top = map.projection.fromScreenLocation(PointF(0f, topPx)).latitude
        val left = map.projection.fromScreenLocation(PointF(leftPx, 0f)).longitude
        val features = FeatureCollection.fromFeatures(
            lines.mapNotNull { line ->
                val at = line.crossing(top, left) ?: return@mapNotNull null
                Feature.fromGeometry(Point.fromLngLat(at.lon, at.lat)).apply { addStringProperty("l", line.label) }
            },
        )
        style.getSourceAs<GeoJsonSource>(LABEL_SOURCE)?.setGeoJson(features) ?: style.addSource(GeoJsonSource(LABEL_SOURCE, features))
    }

    private fun ensureLayers(style: Style, night: Boolean) {
        val color = if (night) "#9ec3e6" else "#1d4f91"
        val lines = style.getLayer(LINES)
        if (lines == null) {
            val layer = LineLayer(LINES, SOURCE).withProperties(lineColor(color), lineWidth(1f), lineOpacity(0.7f))
            if (style.getLayer(BELOW_LABELS) != null) style.addLayerBelow(layer, BELOW_LABELS) else style.addLayer(layer)
        } else {
            lines.setProperties(lineColor(color))
        }
        val labels = style.getLayer(LABELS)
        if (labels == null) {
            // Just under the saved places, over the base labels: grid numbers must read.
            PlaceLayers.addBelow(
                style,
                SymbolLayer(LABELS, LABEL_SOURCE).withProperties(
                    textField(get("l")),
                    textAllowOverlap(true),
                    textIgnorePlacement(true),
                    textFont(arrayOf("Noto Sans Bold")),
                    textSize(11f),
                    textColor(color),
                    textHaloColor(if (night) "#000000" else "#ffffff"),
                    textHaloWidth(1.5f),
                ),
            )
        } else {
            labels.setProperties(textColor(color), textHaloColor(if (night) "#000000" else "#ffffff"))
        }
    }

    private fun remove(style: Style) {
        style.getLayer(LABELS)?.let(style::removeLayer)
        style.getLayer(LINES)?.let(style::removeLayer)
        style.getSource(SOURCE)?.let(style::removeSource)
        style.getSource(LABEL_SOURCE)?.let(style::removeSource)
        made.remove(style)
    }

    private fun inside(inner: BoundingBox, outer: BoundingBox) =
        inner.south >= outer.south && inner.north <= outer.north && inner.west >= outer.west && inner.east <= outer.east

    /** Half again on each side, so small moves don't make the grid again. */
    private fun grow(b: BoundingBox): BoundingBox {
        val dLat = (b.north - b.south) / 2
        val dLon = (b.east - b.west) / 2
        return BoundingBox(
            (b.south - dLat).coerceAtLeast(-85.0),
            (b.west - dLon).coerceAtLeast(-180.0),
            (b.north + dLat).coerceAtMost(85.0),
            (b.east + dLon).coerceAtMost(180.0),
        )
    }
}
