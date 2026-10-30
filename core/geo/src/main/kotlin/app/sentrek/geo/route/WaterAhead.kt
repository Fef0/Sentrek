package app.sentrek.geo.route

import app.sentrek.geo.overlay.PoiKind
import app.sentrek.geo.place.PlaceKind
import kotlin.math.max

/**
 * Water on the route ahead: the next spring or fountain, and how long the stretch without
 * any runs after it. "The last water for 8 km" is what to know before passing it.
 */
data class WaterAhead(
    /** The next water on the route, null when there's none left. */
    val next: UpcomingPlace?,
    /** From [next] (or from here, with none left) to the water after it, or to the end. */
    val dryDistance: Double,
    /** That stretch at the standard pace. */
    val dryMillis: Long,
) {
    /** A stretch long enough to carry water for. */
    val long: Boolean get() = dryDistance >= LONG_DRY_M || dryMillis >= LONG_DRY_MS

    companion object {
        const val LONG_DRY_M = 5_000.0
        const val LONG_DRY_MS = 2 * 3_600_000L

        /** Springs and fountains from the map data, and the user's own water places. */
        fun isWater(place: RoutePlace): Boolean = when (val k = place.kind) {
            is RoutePlace.Kind.Osm -> k.kind == PoiKind.Spring || k.kind == PoiKind.DrinkingWater
            is RoutePlace.Kind.Saved -> k.kind == PlaceKind.Water
            RoutePlace.Kind.Waypoint -> false
        }

        /**
         * The water ahead of [here] (metres along [route]), or null when the route has no
         * water at all: then the map data likely has none here (an area not downloaded), and
         * "no water" would be a guess.
         */
        fun of(route: Route, places: List<RoutePlace>, here: Double): WaterAhead? {
            val water = places.filter(::isWater).sortedBy { it.along }
            if (water.isEmpty()) return null
            val ahead = water.filter { it.along >= here - RoutePlaces.REACHED_M }
            val next = ahead.firstOrNull()?.let { RoutePlaces.upcoming(route, listOf(it), here).first() }
            val from = next?.place?.along ?: here
            val to = ahead.firstOrNull { it.along > from + RoutePlaces.REACHED_M }?.along ?: route.length
            val start = route.remaining(from)
            val end = route.remaining(to)
            val distance = max(0.0, to - from)
            val millis = HikingTime.millis(distance, max(0.0, start.ascent - end.ascent), max(0.0, start.descent - end.descent))
            return WaterAhead(next, distance, millis)
        }
    }
}
