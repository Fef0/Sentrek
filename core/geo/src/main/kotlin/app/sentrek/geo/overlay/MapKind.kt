package app.sentrek.geo.overlay

/**
 * What the map is drawn for. All share the base tiles; [Cycling] and [Winter] each add their
 * own details per downloaded area (cycle routes, pistes), downloaded only where asked for.
 * [key] names their files and is stored: never change it.
 */
enum class MapKind(val key: String) {
    Hiking("hike"),
    Cycling("bike"),
    Winter("ski");

    companion object {
        /** The ones with their own download. */
        val extras: List<MapKind> = listOf(Cycling, Winter)

        fun fromKey(key: String): MapKind? = entries.firstOrNull { it.key == key }
    }
}
