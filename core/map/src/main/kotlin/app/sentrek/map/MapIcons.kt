package app.sentrek.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import androidx.core.graphics.createBitmap
import app.sentrek.core.map.R
import org.maplibre.android.maps.Style

/**
 * Map icons (from OpenStreetMap Carto, CC0), drawn as white round badges with a coloured
 * symbol: readable on rock, snow and forest. Registered as `poi-<name>` style images, so
 * they work offline and need no sprite server.
 */
object MapIcons {
    const val PREFIX = "poi-"

    private enum class Group(val color: Int) {
        Lodging(0xFF1565C0.toInt()),
        Water(0xFF0277BD.toInt()),
        Nature(0xFF5D4037.toInt()),
        History(0xFF6A1B9A.toInt()),
        Signs(0xFFC62828.toInt()),
        Services(0xFF455A64.toInt()),
        Food(0xFFD84315.toInt()),
    }

    private val ICONS: Map<String, Pair<Int, Group>> = mapOf(
        "alpine_hut" to (R.drawable.poi_alpine_hut to Group.Lodging),
        "wilderness_hut" to (R.drawable.poi_wilderness_hut to Group.Lodging),
        "shelter" to (R.drawable.poi_shelter to Group.Lodging),
        "camping" to (R.drawable.poi_camping to Group.Lodging),
        "hotel" to (R.drawable.poi_hotel to Group.Lodging),
        "chalet" to (R.drawable.poi_chalet to Group.Lodging),
        "spring" to (R.drawable.poi_spring to Group.Water),
        "drinking_water" to (R.drawable.poi_drinking_water to Group.Water),
        "waterfall" to (R.drawable.poi_waterfall to Group.Water),
        "cave" to (R.drawable.poi_cave to Group.Nature),
        "viewpoint" to (R.drawable.poi_viewpoint to Group.Nature),
        "peak" to (R.drawable.poi_peak to Group.Nature),
        "saddle" to (R.drawable.poi_saddle to Group.Nature),
        "picnic" to (R.drawable.poi_picnic to Group.Nature),
        "memorial" to (R.drawable.poi_memorial to Group.History),
        "monument" to (R.drawable.poi_monument to Group.History),
        "cross" to (R.drawable.poi_cross to Group.History),
        "shrine" to (R.drawable.poi_shrine to Group.History),
        "archaeological_site" to (R.drawable.poi_archaeological_site to Group.History),
        "castle" to (R.drawable.poi_castle to Group.History),
        "place_of_worship" to (R.drawable.poi_place_of_worship to Group.History),
        "guidepost" to (R.drawable.poi_guidepost to Group.Signs),
        "board" to (R.drawable.poi_board to Group.Signs),
        "emergency_phone" to (R.drawable.poi_emergency_phone to Group.Signs),
        "restaurant" to (R.drawable.poi_restaurant to Group.Food),
        "cafe" to (R.drawable.poi_cafe to Group.Food),
        "bar" to (R.drawable.poi_bar to Group.Food),
        "parking" to (R.drawable.poi_parking to Group.Services),
        "toilets" to (R.drawable.poi_toilets to Group.Services),
    )

    /** Call after each style load: images belong to the style. */
    fun install(context: Context, style: Style) {
        val density = context.resources.displayMetrics.density
        for ((name, icon) in ICONS) {
            style.addImage(PREFIX + name, badge(context, icon.first, icon.second.color, density))
        }
    }

    private fun badge(context: Context, @DrawableRes res: Int, color: Int, density: Float): Bitmap {
        val size = (BADGE_DP * density).toInt()
        val stroke = 1.5f * density
        val bitmap = createBitmap(size, size)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val radius = size / 2f - stroke / 2

        paint.style = Paint.Style.FILL
        paint.color = 0xFFFFFFFF.toInt()
        canvas.drawCircle(size / 2f, size / 2f, radius, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = stroke
        paint.color = color
        canvas.drawCircle(size / 2f, size / 2f, radius, paint)

        val inset = (size * 0.22f).toInt()
        ContextCompat.getDrawable(context, res)!!.mutate().apply {
            setTint(color)
            setBounds(inset, inset, size - inset, size - inset)
            draw(canvas)
        }
        return bitmap
    }

    private const val BADGE_DP = 22
}
