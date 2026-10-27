package app.sentrek.map

import app.sentrek.geo.BoundingBox
import app.sentrek.geo.RegionCell
import app.sentrek.geo.RegionGrid
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression.get
import org.maplibre.android.style.expressions.Expression.literal
import org.maplibre.android.style.expressions.Expression.match
import org.maplibre.android.style.expressions.Expression.rgba
import org.maplibre.android.style.expressions.Expression.stop
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory.fillColor
import org.maplibre.android.style.layers.PropertyFactory.lineColor
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
import org.maplibre.android.style.layers.PropertyFactory.textAllowOverlap
import org.maplibre.android.style.layers.PropertyFactory.textColor
import org.maplibre.android.style.layers.PropertyFactory.textField
import org.maplibre.android.style.layers.PropertyFactory.textFont
import org.maplibre.android.style.layers.PropertyFactory.textHaloColor
import org.maplibre.android.style.layers.PropertyFactory.textHaloWidth
import org.maplibre.android.style.layers.PropertyFactory.textSize
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import org.maplibre.geojson.Polygon
import kotlin.math.ceil
import kotlin.math.floor

/** How a cell is drawn on the region picker. */
enum class CellLook { Selected, Queued, Downloading, Ready, Failed }

/** Draws the Trekarta-style download grid and each cell's state. */
object RegionGridLayer {
    /** Below this zoom 0.5° cells are too small to tap reliably. */
    const val MIN_ZOOM = 6.0

    private const val CELLS = "region-cells"
    private const val LINES = "region-lines"
    private const val LABELS = "region-labels"
    private const val FIRST_LABEL_LAYER = "water-name"

    fun install(style: Style) {
        if (style.getSource(CELLS) != null) return
        style.addSource(GeoJsonSource(CELLS))
        style.addSource(GeoJsonSource(LINES))
        style.addSource(GeoJsonSource(LABELS))
        // Fill under the labels so peak and place names stay readable.
        style.addLayerBelow(
            FillLayer("$CELLS-fill", CELLS).withProperties(
                fillColor(
                    match(
                        get("look"), rgba(0, 0, 0, 0f),
                        stop(literal(CellLook.Selected.name), rgba(232, 93, 4, 0.40f)),
                        stop(literal(CellLook.Queued.name), rgba(240, 180, 0, 0.30f)),
                        stop(literal(CellLook.Downloading.name), rgba(240, 180, 0, 0.40f)),
                        stop(literal(CellLook.Ready.name), rgba(46, 125, 50, 0.30f)),
                        stop(literal(CellLook.Failed.name), rgba(200, 30, 40, 0.35f)),
                    ),
                ),
            ),
            FIRST_LABEL_LAYER,
        )
        style.addLayer(
            LineLayer("$LINES-line", LINES).withProperties(
                lineColor(rgba(40, 40, 40, 0.6f)),
                lineWidth(1f),
            ),
        )
        style.addLayer(
            // A point per cell: polygon labels would repeat once per tile.
            SymbolLayer("$LABELS-text", LABELS).withProperties(
                textField(get("label")),
                textFont(arrayOf("Noto Sans Bold")),
                textSize(15f),
                textColor(rgba(17, 17, 17, 1f)),
                textHaloColor(rgba(255, 255, 255, 1f)),
                textHaloWidth(2f),
                textAllowOverlap(true),
            ),
        )
    }

    /**
     * Redraws grid lines covering [view] and fills for [cells] (look + optional label such as "42%").
     * When [view] is null (zoomed out too far) only the coloured cells are shown.
     */
    fun update(style: Style, view: BoundingBox?, cells: Map<RegionCell, Pair<CellLook, String?>>) {
        (style.getSource(CELLS) as? GeoJsonSource)?.setGeoJson(
            FeatureCollection.fromFeatures(cells.map { (cell, look) -> cellFeature(cell, look.first) }),
        )
        (style.getSource(LABELS) as? GeoJsonSource)?.setGeoJson(
            FeatureCollection.fromFeatures(
                cells.mapNotNull { (cell, look) ->
                    val label = look.second ?: return@mapNotNull null
                    val c = cell.bbox.center
                    Feature.fromGeometry(Point.fromLngLat(c.lon, c.lat)).apply { addStringProperty("label", label) }
                },
            ),
        )
        (style.getSource(LINES) as? GeoJsonSource)?.setGeoJson(
            FeatureCollection.fromFeatures(view?.let(::gridLines) ?: emptyList()),
        )
    }

    private fun cellFeature(cell: RegionCell, look: CellLook): Feature {
        val b = cell.bbox
        val ring = listOf(
            Point.fromLngLat(b.west, b.south), Point.fromLngLat(b.east, b.south),
            Point.fromLngLat(b.east, b.north), Point.fromLngLat(b.west, b.north),
            Point.fromLngLat(b.west, b.south),
        )
        return Feature.fromGeometry(Polygon.fromLngLats(listOf(ring))).apply {
            addStringProperty("look", look.name)
        }
    }

    private fun gridLines(view: BoundingBox): List<Feature> {
        val step = RegionGrid.CELL_DEG
        val south = floor(view.south / step) * step
        val north = ceil(view.north / step) * step
        val west = floor(view.west / step) * step
        val east = ceil(view.east / step) * step
        val lines = ArrayList<Feature>()
        var lat = south
        while (lat <= north) {
            lines += Feature.fromGeometry(LineString.fromLngLats(listOf(Point.fromLngLat(west, lat), Point.fromLngLat(east, lat))))
            lat += step
        }
        var lon = west
        while (lon <= east) {
            lines += Feature.fromGeometry(LineString.fromLngLats(listOf(Point.fromLngLat(lon, south), Point.fromLngLat(lon, north))))
            lon += step
        }
        return lines
    }
}
