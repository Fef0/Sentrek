package app.sentrek.geo.place

import app.sentrek.geo.LatLon
import java.util.Locale

/**
 * A point the user saved: a camp spot, a spring, a parking… [color] is ARGB, null for the
 * kind's own colour. [time] is when it was saved (ms), 0 if unknown. [photos] are file names
 * in the app's photo folder, in the order taken.
 */
data class Place(
    val id: String,
    val name: String,
    val kind: PlaceKind,
    val position: LatLon,
    val note: String = "",
    val color: Int? = null,
    val time: Long = 0,
    val photos: List<String> = emptyList(),
) {
    val displayColor: Int get() = color ?: kind.color
}

/** Place categories. [id] is what goes in GPX `<type>`, so other apps see something readable. */
enum class PlaceKind(val id: String, val color: Int) {
    Star("star", 0xFFC2185B.toInt()),
    Camp("camp", 0xFF2E7D32.toInt()),
    Water("water", 0xFF0277BD.toInt()),
    Shelter("shelter", 0xFF6D4C41.toInt()),
    View("viewpoint", 0xFF7B1FA2.toInt()),
    Peak("peak", 0xFF455A64.toInt()),
    Parking("parking", 0xFF3949AB.toInt()),
    Food("food", 0xFFE65100.toInt()),
    Danger("danger", 0xFFD32F2F.toInt());

    companion object {
        /**
         * Best guess from a GPX type or symbol, or an OSM value: "camp_site", Garmin's
         * "Campground", "Drinking Water"… Anything unknown is a [Star].
         */
        fun guess(text: String?): PlaceKind {
            val t = text?.lowercase(Locale.ROOT)?.replace('_', ' ') ?: return Star
            entries.firstOrNull { it.id == t }?.let { return it }
            return KEYWORDS.firstOrNull { (words, _) -> words.any { it in t } }?.second ?: Star
        }

        private val KEYWORDS = listOf(
            listOf("camp", "bivouac", "tent") to Camp,
            listOf("water", "spring", "fountain") to Water,
            listOf("shelter", "hut", "lodg", "chalet", "hotel", "hostel") to Shelter,
            listOf("view", "scenic", "overlook") to View,
            listOf("peak", "summit", "saddle", "volcano") to Peak,
            listOf("parking") to Parking,
            listOf("restaurant", "food", "cafe", "bar", "pub", "picnic", "dining") to Food,
            listOf("danger", "hazard", "warning", "skull", "caution") to Danger,
        )
    }
}
