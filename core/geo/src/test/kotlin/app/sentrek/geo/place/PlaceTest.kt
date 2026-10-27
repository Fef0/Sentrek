package app.sentrek.geo.place

import app.sentrek.geo.LatLon
import app.sentrek.geo.gpx.Gpx
import app.sentrek.geo.gpx.GpxWaypoint
import app.sentrek.geo.gpx.Kml
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.StringWriter

class PlaceTest {
    @Test
    fun `waypoints only file round-trips note, type and time`() {
        val waypoints = listOf(
            GpxWaypoint("Camp <B>", LatLon(45.97, 7.65, 2100.0), note = "Flat, sheltered & dry", type = "camp", time = 1_793_000_000_000),
            GpxWaypoint("Spring", LatLon(45.98, 7.66), type = "water"),
        )
        val out = StringWriter()
        Gpx.write(out, "Places", emptyList(), "Sentrek", waypoints)
        assertTrue("no track element", "<trk>" !in out.toString())

        val read = Gpx.read(out.toString().byteInputStream())
        assertEquals(waypoints, read.waypoints)
        assertTrue(read.tracks.isEmpty())
    }

    @Test
    fun `garmin symbol is the type when there is none, cmt is the note`() {
        val gpx = """
            <gpx version="1.1" xmlns="http://www.topografix.com/GPX/1/1">
              <wpt lat="46.1" lon="7.2"><name>Lot</name><cmt>Free</cmt><sym>Parking Area</sym></wpt>
            </gpx>
        """.trimIndent()
        val w = Gpx.read(gpx.byteInputStream()).waypoints.single()
        assertEquals("Free", w.note)
        assertEquals("Parking Area", w.type)
        assertEquals(PlaceKind.Parking, PlaceKind.guess(w.type))
    }

    @Test
    fun `kml placemark description is the note`() {
        val kml = """
            <kml xmlns="http://www.opengis.net/kml/2.2"><Document>
              <Placemark><name>Bivy</name><description>Under the boulder</description>
                <Point><coordinates>7.5,46.0,0</coordinates></Point></Placemark>
            </Document></kml>
        """.trimIndent()
        val w = Kml.read(kml.byteInputStream()).waypoints.single()
        assertEquals("Under the boulder", w.note)
    }

    @Test
    fun `kind guesses`() {
        assertEquals(PlaceKind.Camp, PlaceKind.guess("camp_site"))
        assertEquals(PlaceKind.Camp, PlaceKind.guess("Campground"))
        assertEquals(PlaceKind.Water, PlaceKind.guess("Drinking Water"))
        assertEquals(PlaceKind.Water, PlaceKind.guess("spring"))
        assertEquals(PlaceKind.Shelter, PlaceKind.guess("alpine_hut"))
        assertEquals(PlaceKind.View, PlaceKind.guess("Scenic Area"))
        assertEquals(PlaceKind.Peak, PlaceKind.guess("Summit"))
        assertEquals(PlaceKind.Danger, PlaceKind.guess("Skull and Crossbones"))
        assertEquals(PlaceKind.Food, PlaceKind.guess("restaurant"))
        assertEquals(PlaceKind.Star, PlaceKind.guess("Flag, Blue"))
        assertEquals(PlaceKind.Star, PlaceKind.guess(null))
        // Our own ids come back as they were written.
        PlaceKind.entries.forEach { assertEquals(it, PlaceKind.guess(it.id)) }
    }
}
