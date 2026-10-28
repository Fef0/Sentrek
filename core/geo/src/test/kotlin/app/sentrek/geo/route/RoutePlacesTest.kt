package app.sentrek.geo.route

import app.sentrek.geo.BoundingBox
import app.sentrek.geo.LatLon
import app.sentrek.geo.overlay.OverlayPois
import app.sentrek.geo.overlay.PoiKind
import org.junit.Assert.assertEquals
import org.junit.Test

class RoutePlacesTest {
    // Due east along the equator-ish line at 46°N: 0.001° of longitude ≈ 77 m.
    private val route = Route((0..40).map { LatLon(46.0, 7.0 + it * 0.001, 1_000.0 + it * 10) })
    private val hut = RoutePlace.Kind.Osm(PoiKind.AlpineHut)

    @Test
    fun `places near the route in walking order, far ones left out`() {
        val places = RoutePlaces.along(
            route,
            listOf(
                RoutePlaces.Candidate("Far hut", hut, LatLon(46.01, 7.02)), // ~1.1 km north
                RoutePlaces.Candidate("Pass", RoutePlace.Kind.Osm(PoiKind.Pass), LatLon(46.0005, 7.03)), // ~55 m off
                RoutePlaces.Candidate("Hut", hut, LatLon(46.0, 7.01)),
                RoutePlaces.Candidate("My camp", RoutePlace.Kind.Waypoint, LatLon(46.003, 7.02)), // ~330 m, a waypoint
            ),
        )
        assertEquals(listOf("Hut", "My camp", "Pass"), places.map { it.name })
    }

    @Test
    fun `one place listed once`() {
        val places = RoutePlaces.along(
            route,
            listOf(
                RoutePlaces.Candidate("Rifugio", RoutePlace.Kind.Waypoint, LatLon(46.0, 7.01)),
                RoutePlaces.Candidate("RIFUGIO", hut, LatLon(46.0001, 7.0101)),
                RoutePlaces.Candidate(null, hut, LatLon(46.0, 7.0103)),
                // Something else at the same spot stays.
                RoutePlaces.Candidate(null, RoutePlace.Kind.Osm(PoiKind.Spring), LatLon(46.0, 7.0102)),
            ),
        )
        assertEquals(listOf("Rifugio", null), places.map { it.name })
    }

    @Test
    fun `upcoming places with the way there`() {
        val places = RoutePlaces.along(
            route,
            listOf(
                RoutePlaces.Candidate("A", hut, LatLon(46.0, 7.005)),
                RoutePlaces.Candidate("B", hut, LatLon(46.0, 7.03)),
            ),
        )
        val atA = places[0].along
        // Just past A: it still counts as reached.
        val ahead = RoutePlaces.upcoming(route, places, atA + 10)
        assertEquals(listOf("A", "B"), ahead.map { it.place.name })
        val toB = ahead[1]
        assertEquals(places[1].along - atA - 10, toB.distance, 1e-6)
        // 10 m up per 77 m point: 24.87 points between them.
        assertEquals(248.0, toB.ascent, 15.0)
        assertEquals(listOf("B"), RoutePlaces.upcoming(route, places, atA + 100).map { it.place.name })
    }

    @Test
    fun `points read from an overlay file`() {
        val json = """
            {"type":"FeatureCollection","features":[
            {"type":"Feature","properties":{"v":0,"id":"way/1"},"geometry":{"type":"LineString","coordinates":[[7.0,46.0],[7.1,46.1]]}},
            {"type":"Feature","properties":{"k":"saddle","n":"Col","ele":"2 500","id":"node/2"},"geometry":{"type":"Point","coordinates":[7.01,46.0]}},
            {"type":"Feature","properties":{"k":"alpine_hut","n":"Hut","ele":"2210 m"},"geometry":{"type":"Point","coordinates":[7.02,46.0]}},
            {"type":"Feature","properties":{"k":"guidepost"},"geometry":{"type":"Point","coordinates":[7.02,46.0]}},
            {"type":"Feature","properties":{"k":"spring"},"geometry":{"type":"Point","coordinates":[9.0,46.0]}}
            ]}
        """.trimIndent()
        val pois = OverlayPois.read(
            json.reader(), BoundingBox(45.9, 6.9, 46.1, 7.1), PoiKind.entries.filter { it.landmark }.toSet(),
        )
        assertEquals(listOf(PoiKind.Pass, PoiKind.AlpineHut), pois.map { it.kind })
        assertEquals("Col", pois[0].name)
        assertEquals(2_210.0, pois[1].position.ele!!, 0.0)
    }
}
