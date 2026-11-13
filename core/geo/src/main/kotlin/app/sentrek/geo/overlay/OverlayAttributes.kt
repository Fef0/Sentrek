package app.sentrek.geo.overlay

/** SAC hiking scale (OSM `sac_scale`), T1–T6. */
enum class SacScale(val osm: String, val grade: Int) {
    Hiking("hiking", 1),
    MountainHiking("mountain_hiking", 2),
    DemandingMountainHiking("demanding_mountain_hiking", 3),
    AlpineHiking("alpine_hiking", 4),
    DemandingAlpineHiking("demanding_alpine_hiking", 5),
    DifficultAlpineHiking("difficult_alpine_hiking", 6);

    companion object {
        private val byOsm = entries.associateBy { it.osm } + ("strolling" to Hiking)

        fun fromOsm(value: String?): SacScale? = value?.let { byOsm[it.trim()] }
    }
}

/** OSM `trail_visibility`, 1 (excellent) to 6 (no visible trail). */
enum class TrailVisibility(val osm: String, val level: Int) {
    Excellent("excellent", 1),
    Good("good", 2),
    Intermediate("intermediate", 3),
    Bad("bad", 4),
    Horrible("horrible", 5),
    No("no", 6);

    companion object {
        private val byOsm = entries.associateBy { it.osm } + ("very_bad" to Horrible)

        fun fromOsm(value: String?): TrailVisibility? = value?.let { byOsm[it.trim()] }
    }
}

/**
 * Hiking points fetched with the overlay of each downloaded area. The base tiles lack most of
 * them below z14 (or entirely), so downloaded areas show them from [minZoom].
 * [icon] is the map icon name (core/map MapIcons); declaration order is display priority when
 * icons compete for space: huts first, then water, landmarks, signs.
 * [onMap] false: the base style draws them already (peaks, passes); they are fetched only to
 * be listed along a route. [landmark]: worth listing as a place ahead on a followed route.
 */
enum class PoiKind(val icon: String, val minZoom: Int, val onMap: Boolean = true, val landmark: Boolean = false) {
    AlpineHut("alpine_hut", 11, landmark = true),
    WildernessHut("wilderness_hut", 11, landmark = true),
    Shelter("shelter", 11, landmark = true),
    Spring("spring", 12, landmark = true),
    DrinkingWater("drinking_water", 12, landmark = true),
    Cave("cave", 12, landmark = true),
    Viewpoint("viewpoint", 12, landmark = true),
    Waterfall("waterfall", 12, landmark = true),
    Castle("castle", 12),
    Camping("camping", 12, landmark = true),
    Guidepost("guidepost", 13),
    Cross("cross", 13),
    Shrine("shrine", 13),
    Ruins("archaeological_site", 13),
    Monument("monument", 13),
    Memorial("memorial", 13),
    Board("board", 13),
    EmergencyPhone("emergency_phone", 13, landmark = true),
    Peak("peak", 12, onMap = false, landmark = true),
    Pass("saddle", 12, onMap = false, landmark = true);

    companion object {
        /** Classifies a feature from its OSM tags; null when it isn't one we show. */
        fun classify(tags: Map<String, String>): PoiKind? = when {
            tags["tourism"] == "alpine_hut" -> AlpineHut
            tags["tourism"] == "wilderness_hut" -> WildernessHut
            tags["amenity"] == "shelter" && tags["shelter_type"] in MOUNTAIN_SHELTERS -> Shelter
            tags["natural"] == "spring" -> Spring
            tags["amenity"] == "drinking_water" || tags["amenity"] == "water_point" -> DrinkingWater
            tags["natural"] == "cave_entrance" -> Cave
            tags["tourism"] == "viewpoint" -> Viewpoint
            tags["waterway"] == "waterfall" -> Waterfall
            tags["historic"] == "castle" -> Castle
            tags["tourism"] == "camp_site" -> Camping
            tags["information"] == "guidepost" -> Guidepost
            tags["historic"] == "wayside_cross" || tags["man_made"] == "cross" -> Cross
            tags["historic"] == "wayside_shrine" -> Shrine
            tags["historic"] == "ruins" || tags["historic"] == "archaeological_site" -> Ruins
            tags["historic"] == "monument" -> Monument
            tags["historic"] == "memorial" -> Memorial
            tags["information"] == "board" || tags["information"] == "map" -> Board
            tags["emergency"] == "phone" -> EmergencyPhone
            tags["natural"] == "peak" -> Peak
            tags["natural"] == "saddle" || tags["mountain_pass"] == "yes" -> Pass
            else -> null
        }

        /** Excludes bus stops and picnic roofs. */
        val MOUNTAIN_SHELTERS = setOf("basic_hut", "weather_shelter", "lean_to", "rock_shelter")
    }
}

/** OSM `via_ferrata_scale`: 0 (very easy) to 6 (beyond extremely difficult). */
object ViaFerrataScale {
    const val MAX = 6

    /** The grade, from "3" or "3+" (a half grade counts as the grade below); null when unreadable. */
    fun fromOsm(value: String?): Int? = value?.trim()?.firstOrNull()?.digitToIntOrNull()?.takeIf { it in 0..MAX }
}
