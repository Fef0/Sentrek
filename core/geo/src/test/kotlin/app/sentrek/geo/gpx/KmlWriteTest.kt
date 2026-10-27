package app.sentrek.geo.gpx

import app.sentrek.geo.LatLon
import app.sentrek.geo.track.TrackPoint
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.StringWriter

class KmlWriteTest {
    @Test
    fun `written kml reads back the same lines, heights and waypoints`() {
        val segments = listOf(
            listOf(TrackPoint(LatLon(45.9761, 7.6589, 2583.2), 0), TrackPoint(LatLon(45.9772, 7.6601, 2601.0), 0)),
            listOf(TrackPoint(LatLon(45.98, 7.67, 2700.5), 0), TrackPoint(LatLon(45.981, 7.671), 0)),
        )
        val waypoints = listOf(GpxWaypoint("Hut & spring", LatLon(45.978, 7.662, 2650.0), note = "Open <June>"))
        val out = StringWriter()
        Kml.write(out, "Schwarzsee \"loop\"", segments, waypoints)

        val read = Kml.read(out.toString().byteInputStream())
        assertEquals("Schwarzsee \"loop\"", read.name)
        assertEquals(segments, read.tracks.single().segments)
        assertEquals(waypoints, read.waypoints)
    }
}
