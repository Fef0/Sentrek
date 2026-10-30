package app.sentrek.geo.route

import app.sentrek.geo.LatLon
import app.sentrek.geo.overlay.PoiKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WaterAheadTest {
    // Due east at 46°N, flat: 0.001° of longitude ≈ 77 m, 200 points ≈ 15.4 km.
    private val route = Route((0..200).map { LatLon(46.0, 7.0 + it * 0.001, 1_000.0) })
    private val spring = RoutePlace.Kind.Osm(PoiKind.Spring)
    private val hut = RoutePlace.Kind.Osm(PoiKind.AlpineHut)

    private fun at(i: Int, kind: RoutePlace.Kind = spring, name: String? = null) =
        RoutePlace(name, kind, LatLon(46.0, 7.0 + i * 0.001), route.locate(LatLon(46.0, 7.0 + i * 0.001)).along)

    @Test
    fun `the last water before a long dry stretch`() {
        val places = listOf(at(10, name = "Fonte"), at(20), at(30, hut), at(180))
        val water = WaterAhead.of(route, places, here = at(15).along)!!
        // Next is the one at 20; nothing again until 180: about 12 km.
        assertEquals(at(20).along, water.next!!.place.along, 1.0)
        assertEquals((180 - 20) * 77.0, water.dryDistance, 300.0)
        assertTrue(water.long)
    }

    @Test
    fun `water close together is no worry`() {
        val places = (0..200 step 20).map { at(it) }
        assertFalse(WaterAhead.of(route, places, here = 0.0)!!.long)
    }

    @Test
    fun `none left, from here to the end`() {
        val water = WaterAhead.of(route, listOf(at(10)), here = at(50).along)!!
        assertNull(water.next)
        assertEquals((200 - 50) * 77.0, water.dryDistance, 300.0)
    }

    @Test
    fun `no water on the whole route says nothing`() {
        assertNull(WaterAhead.of(route, listOf(at(10, hut)), here = 0.0))
    }
}
