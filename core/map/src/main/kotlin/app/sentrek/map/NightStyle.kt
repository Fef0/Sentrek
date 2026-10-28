package app.sentrek.map

import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.PropertyFactory.backgroundColor
import org.maplibre.android.style.layers.PropertyFactory.fillColor
import org.maplibre.android.style.layers.PropertyFactory.fillOutlineColor
import org.maplibre.android.style.layers.PropertyFactory.hillshadeAccentColor
import org.maplibre.android.style.layers.PropertyFactory.hillshadeHighlightColor
import org.maplibre.android.style.layers.PropertyFactory.hillshadeShadowColor
import org.maplibre.android.style.layers.PropertyFactory.lineColor
import org.maplibre.android.style.layers.PropertyFactory.lineOpacity
import org.maplibre.android.style.layers.PropertyFactory.textColor
import org.maplibre.android.style.layers.PropertyFactory.textHaloColor
import org.maplibre.android.style.layers.PropertyValue
import org.maplibre.android.maps.Style

/**
 * Night colours for the hiking style (hiking.json): black land, dim relief, light labels and
 * bright trails. Black pixels are off on OLED screens. Applied over the loaded day style, so
 * there is one style file and offline downloads stay the same.
 */
internal object NightStyle {
    private const val HALO = "#000000"
    private val textLayers = mapOf(
        "water-name" to "#6fb0e0",
        "path-name" to "#f08a90",
        "poi-hiking" to "#e8dccb",
        "poi-landmarks" to "#e8dccb",
        "poi-detail" to "#e8dccb",
        "peak" to "#e8dccb",
        "place-village" to "#d8d8d8",
        "place-town" to "#f0f0f0",
        "place-country" to "#c49ac4",
    )

    fun apply(style: Style) {
        set(style, "background", backgroundColor("#000000"))
        set(style, "landcover-wood", fillColor("#0f1f0d"))
        set(style, "landcover-grass", fillColor("#121a0c"))
        set(style, "landcover-wetland", fillColor("#0e1a18"))
        set(style, "landcover-rock", fillColor("#1a1917"))
        set(style, "landcover-ice", fillColor("#1b2229"), fillOutlineColor("#3a5566"))
        set(style, "landuse-residential", fillColor("#1a1715"))
        set(style, "park-outline", lineColor("#3c6b2e"))
        // Lit slopes in dim grey: the relief still reads, the rest stays black.
        set(
            style, "hillshade",
            hillshadeShadowColor(arrayOf("#000000")), hillshadeHighlightColor(arrayOf("#4a4a4a")), hillshadeAccentColor("#000000"),
        )
        set(style, "water", fillColor("#0e2233"))
        set(style, "waterway", lineColor("#2f6d99"))
        set(style, "boundary-country", lineColor("#7a4f7a"))
        set(style, "building", fillColor("#2a2622"), fillOutlineColor("#3a342e"))
        set(style, "road-casing", lineColor("#2e2924"))
        set(style, "road-minor", lineColor("#6b6b6b"))
        set(
            style, "road-major",
            lineColor(Expression.raw("""["match",["get","class"],["motorway","trunk"],"#b0703a","primary","#a8894d","#8a8360"]""")),
        )
        set(
            style, "hiking-route",
            lineColor(Expression.raw("""["match",["get","route_1_network"],["iwn","nwn"],"#b85cd6","rwn","#4f8fe0","lwn","#e0b030","#ffffff"]""")),
            lineOpacity(0.55f),
        )
        set(style, "track", lineColor("#b07a45"))
        set(style, "path", lineColor("#e0484f"))
        set(style, "aerialway", lineColor("#aaaaaa"))
        for ((id, color) in textLayers) set(style, id, textColor(color), textHaloColor(HALO))
    }

    private fun set(style: Style, layerId: String, vararg properties: PropertyValue<*>) {
        style.getLayer(layerId)?.setProperties(*properties)
    }
}
