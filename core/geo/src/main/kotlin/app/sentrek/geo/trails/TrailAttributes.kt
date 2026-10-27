package app.sentrek.geo.trails

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
