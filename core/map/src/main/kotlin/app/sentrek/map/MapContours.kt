package app.sentrek.map

import app.sentrek.geo.dem.Contours
import app.sentrek.geo.dem.TerrainTiles
import app.sentrek.geo.dem.Terrarium
import app.sentrek.geo.units.ElevationUnit
import app.sentrek.geo.units.UnitFormat
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression.eq
import org.maplibre.android.style.expressions.Expression.get
import org.maplibre.android.style.expressions.Expression.interpolate
import org.maplibre.android.style.expressions.Expression.linear
import org.maplibre.android.style.expressions.Expression.literal
import org.maplibre.android.style.expressions.Expression.stop
import org.maplibre.android.style.expressions.Expression.switchCase
import org.maplibre.android.style.expressions.Expression.toString
import org.maplibre.android.style.expressions.Expression.zoom
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory.lineColor
import org.maplibre.android.style.layers.PropertyFactory.lineJoin
import org.maplibre.android.style.layers.PropertyFactory.lineOpacity
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
import org.maplibre.android.style.layers.PropertyFactory.symbolPlacement
import org.maplibre.android.style.layers.PropertyFactory.symbolSpacing
import org.maplibre.android.style.layers.PropertyFactory.textColor
import org.maplibre.android.style.layers.PropertyFactory.textField
import org.maplibre.android.style.layers.PropertyFactory.textFont
import org.maplibre.android.style.layers.PropertyFactory.textHaloColor
import org.maplibre.android.style.layers.PropertyFactory.textHaloWidth
import org.maplibre.android.style.layers.PropertyFactory.textMaxAngle
import org.maplibre.android.style.layers.PropertyFactory.textSize
import org.maplibre.android.style.layers.PropertyFactory.visibility
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.CustomGeometrySource
import org.maplibre.android.style.sources.CustomGeometrySourceOptions
import org.maplibre.android.style.sources.GeometryTileProvider
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.floor
import kotlin.math.sinh

/**
 * Contour lines, traced on the phone from the terrain tiles (see [Contours]), in the user's
 * height unit. Faint brown lines just above the hillshade, so they never compete with trails;
 * every fifth line is thicker and carries its height.
 */
object MapContours {
    private const val SOURCE = "contours"
    private const val LINES = "contours-lines"
    private const val LABELS = "contours-labels"

    /** From here in: further out the hillshade alone reads better. */
    private const val MIN_ZOOM = 12

    /** The densest spacing; closer in, MapLibre scales these tiles up. */
    private const val MAX_ZOOM = 15

    /** Above the relief, below water, roads and trails (hiking.json). */
    private const val ABOVE_LAYER = "hillshade"
    private const val BELOW_LABELS = "water-name"

    /** The hillshade of the base style; hidden, the night map is almost all black pixels. */
    fun showRelief(style: Style, show: Boolean) {
        style.getLayer(ABOVE_LAYER)?.setProperties(visibility(if (show) Property.VISIBLE else Property.NONE))
    }

    /** [terrain] null removes the contours. Call again when [unit] or the map colours change. */
    fun sync(style: Style, terrain: TerrainTiles?, unit: ElevationUnit, night: Boolean) {
        style.getLayer(LABELS)?.let(style::removeLayer)
        style.getLayer(LINES)?.let(style::removeLayer)
        style.getSource(SOURCE)?.let(style::removeSource)
        if (terrain == null) return

        val options = CustomGeometrySourceOptions()
            .withMinZoom(MIN_ZOOM)
            .withMaxZoom(MAX_ZOOM)
            .withClip(true)
        style.addSource(CustomGeometrySource(SOURCE, options, Provider(terrain, unit)))

        val line = if (night) "#7a5c40" else "#a5784c"
        val index = eq(get(INDEX), literal(true))
        style.addLayerAbove(
            LineLayer(LINES, SOURCE).withProperties(
                lineColor(line),
                lineJoin(Property.LINE_JOIN_ROUND),
                lineOpacity(if (night) 0.7f else 0.6f),
                lineWidth(
                    interpolate(
                        linear(), zoom(),
                        stop(MIN_ZOOM, switchCase(index, literal(0.9f), literal(0.4f))),
                        stop(16, switchCase(index, literal(1.6f), literal(0.8f))),
                    ),
                ),
            ),
            ABOVE_LAYER,
        )
        style.addLayerBelow(
            SymbolLayer(LABELS, SOURCE).withFilter(index).withProperties(
                symbolPlacement(Property.SYMBOL_PLACEMENT_LINE),
                symbolSpacing(350f),
                textField(toString(get(LEVEL))),
                textFont(arrayOf("Noto Sans Regular")),
                textSize(interpolate(linear(), zoom(), stop(13, 9f), stop(16, 11f))),
                textMaxAngle(30f),
                textColor(if (night) "#a88a6a" else "#8b6340"),
                textHaloColor(if (night) "#000000" else "#f4f1ea"),
                textHaloWidth(1.5f),
            ),
            BELOW_LABELS,
        )
    }

    private const val LEVEL = "e"
    private const val INDEX = "i"

    /** One map tile's contours, from the terrain tile under it (and a border from its neighbours). */
    private class Provider(private val terrain: TerrainTiles, private val unit: ElevationUnit) : GeometryTileProvider {
        override fun getFeaturesForBounds(bounds: LatLngBounds, zoom: Int): FeatureCollection {
            val features = mutableListOf<Feature>()
            try {
                trace(bounds, zoom, features)
            } catch (e: Exception) {
                // A bad tile costs that tile's lines, never the map.
                android.util.Log.w("MapContours", "No contours at z$zoom: ${e.message}")
            }
            return FeatureCollection.fromFeatures(features)
        }

        private fun trace(bounds: LatLngBounds, zoom: Int, out: MutableList<Feature>) {
            val demZoom = minOf(zoom, Terrarium.ZOOM)
            val n = 1 shl zoom
            val x = floor((bounds.center.longitude + 180) / 360 * n).toInt().coerceIn(0, n - 1)
            val y = Terrarium.locate(bounds.center.latitude, bounds.center.longitude, zoom).key.y
            // The map tile is a square of `size` terrain pixels at demZoom.
            val size = Terrarium.TILE_SIZE shr (zoom - demZoom)
            val originX = x * size - BORDER
            val originY = y * size - BORDER
            val width = size + 2 * BORDER

            val loaded = HashMap<Terrarium.TileKey, Terrarium.Tile?>()
            val world = Terrarium.TILE_SIZE shl demZoom
            val centreKey = Terrarium.TileKey(x * size / Terrarium.TILE_SIZE, y * size / Terrarium.TILE_SIZE, demZoom)
            val centre = terrain.tile(centreKey) ?: return
            loaded[centreKey] = centre

            val toUnit = if (unit == ElevationUnit.Feet) (1 / UnitFormat.FOOT_M).toFloat() else 1f
            val grid = FloatArray(width * width)
            for (j in 0 until width) {
                val gy = (originY + j).coerceIn(0, world - 1)
                for (i in 0 until width) {
                    val gx = Math.floorMod(originX + i, world)
                    val key = Terrarium.TileKey(gx / Terrarium.TILE_SIZE, gy / Terrarium.TILE_SIZE, demZoom)
                    // A missing neighbour: repeat the tile's own edge, so lines just end at the border.
                    val tile = loaded.getOrPut(key) { terrain.tile(key) }
                    grid[j * width + i] = toUnit * if (tile != null) {
                        tile[gx % Terrarium.TILE_SIZE, gy % Terrarium.TILE_SIZE]
                    } else {
                        centre[gx - centreKey.x * Terrarium.TILE_SIZE, gy - centreKey.y * Terrarium.TILE_SIZE]
                    }
                }
            }

            val spacing = Contours.spacing(zoom, unit)
            val worldPx = world.toDouble()
            for (raw in Contours.trace(Contours.blur(grid, width, width), width, width, spacing.interval)) {
                val line = Contours.smooth(raw)
                val points = ArrayList<Point>(line.xy.size / 2)
                for (p in 0 until line.xy.size / 2) {
                    // Grid point (i, j) is the centre of terrain pixel (origin + i, origin + j).
                    val px = (originX + line.xy[p * 2] + 0.5) / worldPx
                    val py = (originY + line.xy[p * 2 + 1] + 0.5) / worldPx
                    points += Point.fromLngLat(px * 360 - 180, Math.toDegrees(atan(sinh(PI * (1 - 2 * py)))))
                }
                if (points.size < 2) continue
                val feature = Feature.fromGeometry(LineString.fromLngLats(points))
                feature.addNumberProperty(LEVEL, line.level)
                feature.addBooleanProperty(INDEX, line.level % spacing.index == 0)
                out += feature
            }
        }
    }

    /** Terrain pixels around the tile: one for the lines to reach its edge, one for the blur. */
    private const val BORDER = 2
}
