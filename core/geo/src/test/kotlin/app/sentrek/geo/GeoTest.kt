package app.sentrek.geo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GeoTest {
    @Test
    fun `distance between Rome and Milan is about 477 km`() {
        val rome = LatLon(41.9028, 12.4964)
        val milan = LatLon(45.4642, 9.1900)
        assertEquals(477_000.0, Geo.distance(rome, milan), 3_000.0)
    }

    @Test
    fun `polyline length sums segments`() {
        val a = LatLon(46.0, 7.0)
        val b = LatLon(46.0, 7.01)
        val c = LatLon(46.01, 7.01)
        assertEquals(Geo.distance(a, b) + Geo.distance(b, c), Geo.length(listOf(a, b, c)), 1e-6)
    }

    @Test
    fun `tile coordinates match known values`() {
        // Zoom 10 tile containing the Matterhorn.
        assertEquals(533, TileMath.tileX(7.6586, 10))
        assertEquals(364, TileMath.tileY(45.9763, 10))
    }
}

class RegionGridTest {
    @Test
    fun `cell id round-trips and contains its point`() {
        val p = LatLon(45.9763, 7.6586)
        val cell = RegionGrid.cellAt(p)
        assertEquals(cell, RegionCell.parse(cell.id))
        assertTrue(p in cell.bbox)
    }

    @Test
    fun `cells stay under OpenFreeMap 3000 tile limit up to 70 degrees`() {
        for (lat in listOf(0.0, 30.0, 46.0, 60.0, 69.9)) {
            val count = RegionGrid.cellAt(LatLon(lat, 10.0)).tileCount()
            assertTrue("lat $lat has $count tiles", count < 3_000)
        }
    }

    @Test
    fun `cellsIn covers the whole box`() {
        val cells = RegionGrid.cellsIn(BoundingBox(45.2, 6.8, 46.1, 7.9))
        assertEquals(3 * 3, cells.size)
    }
}
