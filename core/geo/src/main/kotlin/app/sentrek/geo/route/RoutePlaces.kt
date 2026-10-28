package app.sentrek.geo.route

import app.sentrek.geo.BoundingBox
import app.sentrek.geo.Geo
import app.sentrek.geo.LatLon
import app.sentrek.geo.overlay.PoiKind
import app.sentrek.geo.place.PlaceKind
import kotlin.math.cos
import kotlin.math.max

/** Something to look out for on a route: a hut, a spring, a pass, one of the user's places. */
data class RoutePlace(val name: String?, val kind: Kind, val position: LatLon, val along: Double) {
    sealed interface Kind {
        /** A hiking point from the downloaded map data. */
        data class Osm(val kind: PoiKind) : Kind

        /** One of the user's saved places. */
        data class Saved(val kind: PlaceKind, val color: Int) : Kind

        /** A waypoint of the route's own file. */
        data object Waypoint : Kind
    }
}

/** A place still ahead, and what it takes to get there along the route. */
data class UpcomingPlace(val place: RoutePlace, val distance: Double, val ascent: Double, val millis: Long)

object RoutePlaces {
    /** Map points this close to the route are on the way: a hut beside the path, a spring a few steps off. */
    const val NEAR_M = 150.0

    /** The route's own waypoints were put there for it: further off still counts. */
    const val WAYPOINT_NEAR_M = 500.0

    /** Within this, the place is reached, not passed: it stays listed. */
    const val REACHED_M = 30.0

    /** A candidate before it's placed on the route. */
    data class Candidate(val name: String?, val kind: RoutePlace.Kind, val position: LatLon)

    /** The box around [route] and [margin] metres more: candidates outside it can't be near. */
    fun box(route: Route, margin: Double = WAYPOINT_NEAR_M): BoundingBox? {
        if (route.points.isEmpty()) return null
        val south = route.points.minOf { it.lat }
        val north = route.points.maxOf { it.lat }
        val dLat = Math.toDegrees(margin / Geo.EARTH_RADIUS_M)
        val dLon = dLat / max(cos(Math.toRadians(max(kotlin.math.abs(south), kotlin.math.abs(north)))), 0.01)
        return BoundingBox(
            (south - dLat).coerceAtLeast(-90.0),
            (route.points.minOf { it.lon } - dLon).coerceAtLeast(-180.0),
            (north + dLat).coerceAtMost(90.0),
            (route.points.maxOf { it.lon } + dLon).coerceAtMost(180.0),
        )
    }

    /**
     * The [candidates] near [route], in walking order. The same place listed twice (a saved
     * place on a hut, or two map points for one hut) is kept once: the first, which comes from
     * the most personal source when callers list waypoints and saved places first.
     */
    fun along(route: Route, candidates: List<Candidate>): List<RoutePlace> {
        if (route.points.size < 2) return emptyList()
        val box = box(route) ?: return emptyList()
        val placed = mutableListOf<RoutePlace>()
        val seen = mutableListOf<RoutePlace>()
        for (c in candidates) {
            if (c.position !in box) continue
            val position = route.locate(c.position)
            val limit = if (c.kind == RoutePlace.Kind.Waypoint) WAYPOINT_NEAR_M else NEAR_M
            if (position.offset > limit) continue
            val duplicate = seen.any { p ->
                kotlin.math.abs(p.along - position.along) < SAME_PLACE_M && samePlace(p, c)
            }
            val place = RoutePlace(c.name, c.kind, c.position, position.along)
            // Dropped ones count too: an unnamed hut beside the named hut a waypoint stands for.
            seen += place
            if (!duplicate) placed += place
        }
        return placed.sortedBy { it.along }
    }

    /** The [places] from [here] (metres along [route]) on, with the way to each. */
    fun upcoming(route: Route, places: List<RoutePlace>, here: Double): List<UpcomingPlace> {
        val left = route.remaining(here)
        return places.filter { it.along >= here - REACHED_M }.map { place ->
            val there = route.remaining(place.along)
            val distance = max(0.0, place.along - here)
            val ascent = max(0.0, left.ascent - there.ascent)
            val descent = max(0.0, left.descent - there.descent)
            UpcomingPlace(place, distance, ascent, HikingTime.millis(distance, ascent, descent))
        }
    }

    /** The same name, or one unnamed of the same kind: a spring beside a hut is still a spring. */
    private fun samePlace(p: RoutePlace, c: Candidate): Boolean =
        if (p.name != null && c.name != null) p.name.equals(c.name, ignoreCase = true) else p.kind == c.kind

    /** Two candidates this close along the route, and [samePlace], are one place. */
    private const val SAME_PLACE_M = 60.0
}
