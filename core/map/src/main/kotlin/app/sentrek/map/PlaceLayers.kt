package app.sentrek.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import androidx.core.graphics.createBitmap
import app.sentrek.core.map.R
import app.sentrek.geo.place.Place
import app.sentrek.geo.place.PlaceKind
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression.get
import org.maplibre.android.style.expressions.Expression.rgb
import org.maplibre.android.style.layers.Layer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory.iconAllowOverlap
import org.maplibre.android.style.layers.PropertyFactory.iconAnchor
import org.maplibre.android.style.layers.PropertyFactory.iconIgnorePlacement
import org.maplibre.android.style.layers.PropertyFactory.iconImage
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
import org.maplibre.geojson.Point
import kotlin.math.sqrt

/**
 * The user's saved places: coloured pins with the category symbol in white, on top of
 * everything else. Pins are drawn per kind and colour as they're needed.
 */
object PlaceLayers {
    /** Features carry the place id as "place". */
    internal const val LAYER = "places"
    private const val IMAGE = "place-"

    @DrawableRes
    fun icon(kind: PlaceKind): Int = when (kind) {
        PlaceKind.Star -> R.drawable.poi_star
        PlaceKind.Camp -> R.drawable.poi_camping
        PlaceKind.Water -> R.drawable.poi_drinking_water
        PlaceKind.Shelter -> R.drawable.poi_shelter
        PlaceKind.View -> R.drawable.poi_viewpoint
        PlaceKind.Peak -> R.drawable.poi_peak
        PlaceKind.Parking -> R.drawable.poi_parking
        PlaceKind.Food -> R.drawable.poi_restaurant
        PlaceKind.Danger -> R.drawable.poi_danger
    }

    fun sync(context: Context, style: Style, places: List<Place>) {
        val density = context.resources.displayMetrics.density
        for ((kind, color) in places.map { it.kind to it.displayColor }.distinct()) {
            val name = imageName(kind, color)
            if (style.getImage(name) == null) style.addImage(name, pin(context, icon(kind), color, density))
        }
        val features = places.map { place ->
            Feature.fromGeometry(Point.fromLngLat(place.position.lon, place.position.lat)).apply {
                addStringProperty("place", place.id)
                addStringProperty("name", place.name)
                addStringProperty("i", imageName(place.kind, place.displayColor))
            }
        }
        val collection = FeatureCollection.fromFeatures(features)
        val source = style.getSourceAs<GeoJsonSource>(LAYER)
        if (source != null) {
            source.setGeoJson(collection)
            return
        }
        style.addSource(GeoJsonSource(LAYER, collection))
        style.addLayer(
            SymbolLayer(LAYER, LAYER).withProperties(
                iconImage(get("i")),
                iconAnchor(Property.ICON_ANCHOR_BOTTOM),
                iconAllowOverlap(true),
                iconIgnorePlacement(true),
                textField(get("name")),
                textFont(arrayOf("Noto Sans Bold")),
                textSize(12f),
                textAnchor(Property.TEXT_ANCHOR_TOP),
                textOffset(arrayOf(0f, 0.2f)),
                textOptional(true),
                textColor(rgb(33, 33, 33)),
                textHaloColor(rgb(255, 255, 255)),
                textHaloWidth(1.8f),
            ),
        )
    }

    /** Layers added so far by [addBelow]: a layer that must stay on top can tell when another came. */
    internal var added = 0
        private set

    /** Adds [layer] below the places, so they stay on top whatever is added later. */
    internal fun addBelow(style: Style, layer: Layer) {
        added++
        if (style.getLayer(LAYER) != null) style.addLayerBelow(layer, LAYER) else style.addLayer(layer)
    }

    private fun imageName(kind: PlaceKind, color: Int) = IMAGE + kind.id + "-" + Integer.toHexString(color)

    /** A map pin: a circle with the symbol, narrowing to a point at the bottom centre. */
    private fun pin(context: Context, @DrawableRes res: Int, color: Int, density: Float): Bitmap {
        val width = (PIN_WIDTH_DP * density).toInt()
        val height = (PIN_HEIGHT_DP * density).toInt()
        val stroke = 1.5f * density
        val cx = width / 2f
        val r = width / 2f - stroke
        val cy = r + stroke
        val tipY = height - stroke

        // The two sides run from the tip to where they touch the circle.
        val d = tipY - cy
        val cos = r / d
        val sin = sqrt(1 - cos * cos)
        val outline = Path().apply {
            addCircle(cx, cy, r, Path.Direction.CW)
            val side = Path().apply {
                moveTo(cx, tipY)
                lineTo(cx - r * sin, cy + r * cos)
                lineTo(cx + r * sin, cy + r * cos)
                close()
            }
            op(side, Path.Op.UNION)
        }

        val bitmap = createBitmap(width, height)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.style = Paint.Style.FILL
        paint.color = color or 0xFF000000.toInt()
        canvas.drawPath(outline, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = stroke
        paint.color = 0xFFFFFFFF.toInt()
        canvas.drawPath(outline, paint)

        val half = r * 0.62f
        ContextCompat.getDrawable(context, res)!!.mutate().apply {
            setTint(0xFFFFFFFF.toInt())
            setBounds((cx - half).toInt(), (cy - half).toInt(), (cx + half).toInt(), (cy + half).toInt())
            draw(canvas)
        }
        return bitmap
    }

    private const val PIN_WIDTH_DP = 28
    private const val PIN_HEIGHT_DP = 38
}
