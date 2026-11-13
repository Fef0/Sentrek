package app.sentrek.map

import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.HillshadeLayer
import org.maplibre.android.style.layers.Layer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory.backgroundColor
import org.maplibre.android.style.layers.PropertyFactory.fillColor
import org.maplibre.android.style.layers.PropertyFactory.fillOpacity
import org.maplibre.android.style.layers.PropertyFactory.fillOutlineColor
import org.maplibre.android.style.layers.PropertyFactory.hillshadeAccentColor
import org.maplibre.android.style.layers.PropertyFactory.hillshadeShadowColor
import org.maplibre.android.style.layers.PropertyFactory.lineColor
import org.maplibre.android.style.layers.BackgroundLayer
import org.maplibre.android.style.layers.PropertyValue
import java.util.WeakHashMap

/**
 * Winter colours for the hiking style (hiking.json), as on a ski map: white land, grey-green
 * woods, cold blue-grey relief, so the coloured pistes stand out. Set and taken off while the
 * map is open: the colours it replaces are kept and put back. Not on the night map, which
 * has its own dark colours ([NightStyle]).
 */
internal object WinterStyle {
    private val colours: Map<String, List<PropertyValue<*>>> = mapOf(
        "background" to listOf(backgroundColor(MapColors.BACKGROUND_WINTER)),
        "landcover-wood" to listOf(fillColor("#c9d8cf"), fillOpacity(0.9f)),
        "landcover-grass" to listOf(fillColor("#f3f6f9")),
        "landcover-wetland" to listOf(fillColor("#e6eef3")),
        "landcover-rock" to listOf(fillColor("#e3e7eb")),
        "landcover-ice" to listOf(fillColor("#ffffff"), fillOutlineColor("#8fb8d6")),
        "landuse-residential" to listOf(fillColor("#e8e6e3")),
        "water" to listOf(fillColor("#a7c7dd")),
        "hillshade" to listOf(hillshadeShadowColor(arrayOf("#5a6b80")), hillshadeAccentColor("#6a7a8c")),
        "park-outline" to listOf(lineColor("#9fb8a8")),
    )

    /** Each style's colours before winter, by layer. */
    private val saved = WeakHashMap<Style, MutableMap<String, List<PropertyValue<*>>>>()

    fun apply(style: Style, winter: Boolean) {
        val before = saved.getOrPut(style) { HashMap() }
        for ((id, values) in colours) {
            val layer = style.getLayer(id) ?: continue
            if (winter) {
                if (id !in before) before[id] = values.mapNotNull { current(layer, it.name) }
                layer.setProperties(*values.toTypedArray())
            } else {
                before.remove(id)?.let { layer.setProperties(*it.toTypedArray()) }
            }
        }
    }

    /** [layer]'s value of [name] as set now, expression or constant. */
    private fun current(layer: Layer, name: String): PropertyValue<*>? {
        val value: PropertyValue<*> = when (layer) {
            is BackgroundLayer -> layer.backgroundColor
            is FillLayer -> when (name) {
                "fill-color" -> layer.fillColor
                "fill-opacity" -> layer.fillOpacity
                "fill-outline-color" -> layer.fillOutlineColor
                else -> return null
            }
            is HillshadeLayer -> when (name) {
                "hillshade-shadow-color" -> layer.hillshadeShadowColor
                "hillshade-accent-color" -> layer.hillshadeAccentColor
                else -> return null
            }
            is LineLayer -> if (name == "line-color") layer.lineColor else return null
            else -> return null
        }
        val raw = if (value.isExpression) value.expression else value.value
        return raw?.let { PropertyValue(name, it) }
    }
}
