package app.sentrek.map

/**
 * The colours of the lines Sentrek draws, in one place: the map layers and the legend read
 * them from here, so the legend always matches the map. The base style's own path, track
 * and route colours are in hiking.json too (a test checks they match these).
 */
object MapColors {
    /** Paper-map background of each style, for the legend's swatches. */
    const val BACKGROUND = "#f4f1ea"
    const val BACKGROUND_WINTER = "#f8fafc"
    const val BACKGROUND_NIGHT = "#000000"

    // Trail difficulty, coloured like Swiss waymarking.
    const val T1 = "#e6af00"
    const val T2_T3 = "#c81e28"
    const val T4_T6 = "#1e50c8"

    // Paths and tracks without a grade (the base style's).
    const val PATH = "#c0262d"
    const val PATH_NIGHT = "#e0484f"
    const val TRACK = "#8b5a2b"
    const val TRACK_NIGHT = "#b07a45"

    fun ferrata(night: Boolean) = if (night) "#e6e6e6" else "#1f1f1f"

    // Waymarked hiking routes, wide and see-through under the trails.
    const val ROUTE_NATIONAL = "#8e24aa"
    const val ROUTE_REGIONAL = "#1565c0"
    const val ROUTE_LOCAL = "#f9a825"
    const val ROUTE_NATIONAL_NIGHT = "#b85cd6"
    const val ROUTE_REGIONAL_NIGHT = "#4f8fe0"
    const val ROUTE_LOCAL_NIGHT = "#e0b030"
    const val ROUTE_OPACITY = 0.45f
    const val ROUTE_OPACITY_NIGHT = 0.55f

    // Winter map: downhill pistes by difficulty, and the other kinds of run.
    const val PISTE_NOVICE = "#2e9d3a"
    const val PISTE_EASY = "#1e5bd8"
    const val PISTE_INTERMEDIATE = "#d32f2f"
    fun pisteAdvanced(night: Boolean) = if (night) "#e0e0e0" else "#111111"
    const val PISTE_FREERIDE = "#ef6c00"
    const val PISTE_NORDIC = "#00897b"
    const val PISTE_SKITOUR = "#7b1fa2"
    const val PISTE_SLED = "#795548"
    const val PISTE_HIKE = "#c0262d"
    const val PISTE_OTHER = "#757575"

    // Cycling map.
    const val CYCLE_NATIONAL = "#d81b60"
    const val CYCLE_REGIONAL = "#00838f"
    const val CYCLE_LOCAL = "#3949ab"
    const val CYCLE_MTB = "#a67c00"
    const val CYCLEWAY = "#1565c0"
    const val CYCLE_OPACITY = 0.5f

    /** "#rrggbb" as an opaque ARGB colour, for drawing outside the map. */
    fun argb(hex: String): Int = (0xFF000000 or hex.removePrefix("#").toLong(16)).toInt()
}
