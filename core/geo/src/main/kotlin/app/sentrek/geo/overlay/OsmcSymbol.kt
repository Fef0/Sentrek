package app.sentrek.geo.overlay

/**
 * A hiking route's waymark, as OSM's `osmc:symbol` describes it:
 * `waycolor:background[:foreground[:foreground2]][:text:textcolor]`,
 * e.g. `red:red:white_stripe:708:black` (CAI red-white-red with the number).
 * Shapes are `color` or `color_kind` (`white_stripe`, `red_dot`…).
 */
data class OsmcSymbol(
    val background: Shape?,
    val foregrounds: List<Shape>,
    val text: String?,
    val textColor: String?,
) {
    data class Shape(val color: String, val kind: String)

    companion object {
        val COLORS = setOf("black", "blue", "brown", "gray", "green", "orange", "purple", "red", "white", "yellow")

        /** Null when there's nothing drawable in [value]. */
        fun parse(value: String): OsmcSymbol? {
            val parts = value.trim().split(':')
            if (parts.size < 2) return null
            val rest = parts.drop(2)
            val hasText = rest.size >= 3
            val text = if (hasText) rest[rest.size - 2].trim().takeIf { it.isNotEmpty() && it.length <= MAX_TEXT } else null
            val textColor = if (hasText) color(rest.last()) else null
            val symbol = OsmcSymbol(
                background = shape(parts[1]),
                foregrounds = (if (hasText) rest.dropLast(2) else rest).mapNotNull(::shape),
                text = text,
                textColor = textColor ?: "black",
            )
            return symbol.takeIf { it.background != null || it.foregrounds.isNotEmpty() || it.text != null }
        }

        /** A badge in the route network's colour with [ref], for routes without a waymark. */
        fun fallback(network: String, ref: String): String {
            val color = when (network) {
                "iwn", "nwn" -> "purple"
                "rwn" -> "blue"
                else -> "yellow"
            }
            return "$color:$color::$ref:${if (color == "yellow") "black" else "white"}"
        }

        private fun shape(part: String): Shape? {
            val p = part.trim().lowercase()
            val color = color(p.substringBefore('_')) ?: return null
            return Shape(color, if ('_' in p) p.substringAfter('_') else "")
        }

        private fun color(name: String): String? = name.trim().lowercase().let { if (it == "grey") "gray" else it }.takeIf { it in COLORS }

        private const val MAX_TEXT = 5
    }
}
