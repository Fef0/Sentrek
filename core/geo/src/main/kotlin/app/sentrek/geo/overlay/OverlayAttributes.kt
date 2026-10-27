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
 */
enum class PoiKind(val icon: String, val minZoom: Int) {
    AlpineHut("alpine_hut", 11),
    WildernessHut("wilderness_hut", 11),
    Shelter("shelter", 11),
    Spring("spring", 12),
    DrinkingWater("drinking_water", 12),
    Cave("cave", 12),
    Viewpoint("viewpoint", 12),
    Waterfall("waterfall", 12),
    Castle("castle", 12),
    Camping("camping", 12),
    Guidepost("guidepost", 13),
    Cross("cross", 13),
    Shrine("shrine", 13),
    Ruins("archaeological_site", 13),
    Monument("monument", 13),
    Memorial("memorial", 13),
    Board("board", 13),
    EmergencyPhone("emergency_phone", 13);

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
            else -> null
        }

        /** Excludes bus stops and picnic roofs. */
        val MOUNTAIN_SHELTERS = setOf("basic_hut", "weather_shelter", "lean_to", "rock_shelter")
    }
}
