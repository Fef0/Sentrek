package app.sentrek.geo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CoordinateGridTest {
    @Test
    fun `utm goes there and back`() {
        for (p in listOf(LatLon(46.4675, 10.3707), LatLon(45.0, 6.1), LatLon(-34.0, 15.0), LatLon(64.0, -21.9))) {
            val zone = Utm.zone(p.lon)
            val (e, n) = Utm.forward(p, zone)
            val back = Utm.inverse(e, n, zone)
            assertEquals(p.lat, back.lat, 1e-7)
            assertEquals(p.lon, back.lon, 1e-7)
        }
    }

    @Test
    fun `utm lines stay in their zone and fall on whole kilometres`() {
        // Across the 12°E zone line, between Bolzano and Cortina.
        val box = BoundingBox(46.45, 11.9, 46.55, 12.1)
        val lines = CoordinateGrid.utm(box, 1_000)
        assertTrue(lines.size > 20)
        for (line in lines) {
            val zone = Utm.zone(line.points[line.points.size / 2].lon)
            for (p in line.points) {
                assertTrue(p.lon >= Utm.west(zone) - 1e-9 && p.lon <= Utm.west(zone) + 6 + 1e-9)
                assertTrue(p.lat >= box.south - 1e-9 && p.lat <= box.north + 1e-9)
            }
        }
        // Northings around 5 145 km here: a line is labelled in kilometres.
        assertTrue(lines.any { it.label == "5150" })
    }

    @Test
    fun `a line's number goes where it crosses the screen edge`() {
        val lines = CoordinateGrid.latLon(BoundingBox(46.4, 11.9, 46.6, 12.1), 10)
        val meridian = lines.single { it.label == "12°E" }
        assertEquals(LatLon(46.55, 12.0), meridian.crossing(46.55, 11.95))
        val parallel = lines.single { it.label == "46°30′N" }
        assertEquals(LatLon(46.5, 11.95), parallel.crossing(46.55, 11.95))
    }

    @Test
    fun `steps grow as the map zooms out`() {
        assertEquals(1_000, CoordinateGrid.utmStep(5.0))
        assertEquals(10_000, CoordinateGrid.utmStep(100.0))
        assertEquals(1, CoordinateGrid.angleStepMinutes(5.0))
        assertEquals(60, CoordinateGrid.angleStepMinutes(1_000.0))
    }

    @Test
    fun `latitude and longitude lines are labelled in degrees and minutes`() {
        val lines = CoordinateGrid.latLon(BoundingBox(46.4, 11.9, 46.6, 12.1), 10)
        assertTrue(lines.any { it.label == "46°30′N" })
        assertTrue(lines.any { it.label == "12°E" })
        assertEquals("0°30′W", CoordinateGrid.angle(-0.5, 'E', 'W'))
    }
}
