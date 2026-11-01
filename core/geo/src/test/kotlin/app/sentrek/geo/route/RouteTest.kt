package app.sentrek.geo.route

import app.sentrek.geo.LatLon
import app.sentrek.geo.gpx.TrackFiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class RouteTest {
    /** ~111 m per 0.001° of latitude. */
    private fun north(lat: Double, ele: Double? = null) = LatLon(lat, 7.0, ele)

    @Test
    fun `profile runs in the walking direction and is thinned out`() {
        val route = Route(listOf(north(46.0, 1000.0), north(46.001, 1100.0), north(46.002, 1050.0)))
        val profile = route.profile()
        assertEquals(listOf(1000.0, 1100.0, 1050.0), profile.map { it.second })
        assertEquals(0.0, profile.first().first, 0.0)
        assertEquals(route.length, profile.last().first, 0.01)
        assertEquals(listOf(1050.0, 1100.0, 1000.0), route.reversed().profile().map { it.second })

        val long = Route((0..1000).map { north(46.0 + it * 0.0001, it.toDouble()) })
        assertTrue(long.profile(maxPoints = 100).size <= 101)
        assertEquals(1000.0, long.profile(maxPoints = 100).last().second, 0.0)
        assertTrue(Route(listOf(north(46.0), north(46.001))).profile().isEmpty())
    }

    @Test
    fun `locates along a straight route`() {
        val route = Route(listOf(north(46.0), north(46.001), north(46.002)))
        assertEquals(222.4, route.length, 0.5)
        // 0.0005° east of the first leg's middle: ~39 m off at 46°N.
        val pos = route.locate(LatLon(46.0005, 7.0005))
        assertEquals(55.6, pos.along, 0.5)
        assertEquals(38.6, pos.offset, 0.5)
    }

    @Test
    fun `out and back keeps to the leg the user is on`() {
        // North 222 m, then back south along almost the same line.
        val route = Route(listOf(north(46.0), north(46.002), LatLon(46.0, 7.00005)))
        val here = LatLon(46.001, 7.00002)
        // Without history either leg could match; with history near the return leg it must stay there.
        val onReturn = route.locate(here, previousAlong = 330.0)
        assertEquals(333.6, onReturn.along, 2.0)
        val onWayOut = route.locate(here, previousAlong = 100.0)
        assertEquals(111.2, onWayOut.along, 2.0)
    }

    @Test
    fun `on a loop, the start wins over the end`() {
        // North, east, back south-west to the start.
        val loop = Route(listOf(north(46.0), north(46.002), LatLon(46.002, 7.002), north(46.0)))
        assertEquals(0.0, loop.locate(LatLon(46.0, 7.00001)).along, 2.0)
        assertEquals(loop.length, loop.locate(LatLon(46.0, 7.00001), previousAlong = loop.length - 50).along, 2.0)
    }

    @Test
    fun `walking a long winding route finds the same place as searching all of it`() {
        // 20 km of zigzag north, a point every ~20 m.
        val route = Route((0..1000).map { LatLon(46.0 + it * 0.00018, 7.0 + (if (it / 25 % 2 == 0) it % 25 else 25 - it % 25) * 0.0002) })
        val random = java.util.Random(7)
        var along: Double? = null
        for (i in 0 until 1000 step 3) {
            val a = route.points[i]
            val b = route.points[i + 1]
            val f = random.nextDouble()
            val here = LatLon(
                a.lat + f * (b.lat - a.lat) + (random.nextDouble() - 0.5) * 0.0002,
                a.lon + f * (b.lon - a.lon) + (random.nextDouble() - 0.5) * 0.0002,
            )
            val found = route.locate(here, along)
            val all = searchAll(route, here, along)
            assertEquals(all.along, found.along, 0.01)
            assertEquals(all.offset, found.offset, 0.01)
            along = found.along
        }
    }

    @Test
    fun `far from the last position, the whole route is searched`() {
        val route = Route((0..100).map { north(46.0 + it * 0.0005) })
        // Last seen 100 m from the start, now 4.4 km along: a lift, a lost signal.
        val pos = route.locate(north(46.04), previousAlong = 100.0)
        assertEquals(4448.0, pos.along, 5.0)
        assertEquals(0.0, pos.offset, 0.5)
    }

    /** The plain search: every segment, nearest within 25 m, then the closest to [previousAlong]. */
    private fun searchAll(route: Route, p: LatLon, previousAlong: Double?): RoutePosition {
        val cumulative = route.points.zipWithNext { a, b -> app.sentrek.geo.Geo.distance(a, b) }.runningFold(0.0, Double::plus)
        val candidates = (0 until route.points.size - 1).map { i ->
            val a = route.points[i]
            val b = route.points[i + 1]
            val kx = app.sentrek.geo.Geo.EARTH_RADIUS_M * Math.toRadians(1.0) * kotlin.math.cos(Math.toRadians(p.lat))
            val ky = app.sentrek.geo.Geo.EARTH_RADIUS_M * Math.toRadians(1.0)
            val ax = (a.lon - p.lon) * kx
            val ay = (a.lat - p.lat) * ky
            val dx = (b.lon - p.lon) * kx - ax
            val dy = (b.lat - p.lat) * ky - ay
            val t = ((-ax * dx - ay * dy) / (dx * dx + dy * dy)).coerceIn(0.0, 1.0)
            RoutePosition(cumulative[i] + t * (cumulative[i + 1] - cumulative[i]), kotlin.math.hypot(ax + t * dx, ay + t * dy))
        }
        val best = candidates.minOf { it.offset }
        val near = candidates.filter { it.offset <= best + 25.0 }
        return if (previousAlong == null) near.minBy { it.along } else near.minBy { kotlin.math.abs(it.along - previousAlong) }
    }

    @Test
    fun `remaining distance, climb and time`() {
        // 2 km north climbing 600 m.
        val route = Route(listOf(north(46.0, 1000.0), north(46.009, 1300.0), north(46.018, 1600.0)))
        val all = route.remaining(0.0)
        assertEquals(2001.5, all.distance, 1.0)
        assertEquals(600.0, all.ascent, 0.01)
        // Vertical 2 h dominates horizontal 0.5 h: 2 + 0.25 = 2.25 h.
        assertEquals(2.25 * 3_600_000, all.millis.toDouble(), 60_000.0)
        val half = route.remaining(route.length / 2)
        assertEquals(300.0, half.ascent, 0.01)
        // Just before the middle point: still ~300 m, not the whole first segment's 300 m more.
        assertEquals(301.0, route.remaining(route.length / 2 - 3.3).ascent, 0.5)
        assertEquals(0.0, route.reversed().ascent, 0.01)
        assertEquals(600.0, route.reversed().descent, 0.01)
    }

    @Test
    fun `off route only after the delay, back only when clearly back`() {
        val d = OffRouteDetector(offM = 50.0, backM = 30.0, delayMs = 20_000)
        assertNull(d.update(80.0, 0))
        assertNull(d.update(80.0, 19_000))
        assertNull(d.update(40.0, 25_000)) // Came back in time: timer resets.
        assertNull(d.update(80.0, 30_000))
        assertEquals(OffRouteDetector.Event.OffRoute, d.update(80.0, 50_000))
        assertNull(d.update(45.0, 51_000)) // Between thresholds: still off.
        assertEquals(OffRouteDetector.Event.BackOnRoute, d.update(20.0, 52_000))
    }

    @Test
    fun `reads kml lines, gx tracks and points`() {
        val kml = """<?xml version="1.0" encoding="UTF-8"?>
            <kml xmlns="http://www.opengis.net/kml/2.2" xmlns:gx="http://www.google.com/kml/ext/2.2">
              <Document><name>Day hike</name>
                <Placemark><name>Path</name>
                  <LineString><coordinates>
                    7.0,46.0,1000 7.001,46.001,1010
                    7.002,46.002,0
                  </coordinates></LineString>
                </Placemark>
                <Placemark><name>Spring</name><Point><coordinates>7.1,46.1</coordinates></Point></Placemark>
                <Placemark><name>Recorded</name>
                  <gx:Track>
                    <when>2026-10-27T10:00:00Z</when><when>2026-10-27T10:01:00Z</when>
                    <gx:coord>7.0 46.0 1000</gx:coord><gx:coord>7.0 46.001 1005</gx:coord>
                  </gx:Track>
                </Placemark>
                <Placemark><name>Area</name><Polygon><outerBoundaryIs><LinearRing>
                  <coordinates>7,46 7.1,46 7.1,46.1 7,46</coordinates>
                </LinearRing></outerBoundaryIs></Polygon></Placemark>
              </Document>
            </kml>"""
        val file = TrackFiles.read(kml.byteInputStream())
        assertEquals("Day hike", file.name)
        assertEquals(listOf("Path", "Recorded"), file.tracks.map { it.name })
        val path = file.tracks[0].segments.single()
        assertEquals(3, path.size)
        assertNull(path[2].position.ele) // 0 means "on the ground" in KML.
        val recorded = file.tracks[1].segments.single()
        assertEquals(60_000L, recorded[1].time - recorded[0].time)
        assertEquals("Spring", file.waypoints.single().name)
    }

    @Test
    fun `reads kmz and rejects other files`() {
        val kml = """<kml xmlns="http://www.opengis.net/kml/2.2"><Placemark><LineString>
            <coordinates>7,46 7.001,46.001</coordinates></LineString></Placemark></kml>"""
        val zipped = ByteArrayOutputStream().also { bytes ->
            ZipOutputStream(bytes).use { zip ->
                zip.putNextEntry(ZipEntry("files/icon.png")); zip.write(byteArrayOf(1, 2, 3)); zip.closeEntry()
                zip.putNextEntry(ZipEntry("doc.kml")); zip.write(kml.toByteArray()); zip.closeEntry()
            }
        }.toByteArray()
        assertEquals(2, TrackFiles.read(zipped.inputStream()).tracks.single().segments.single().size)

        assertThrows(TrackFiles.UnsupportedFormatException::class.java) {
            TrackFiles.read("<html><body>not a track</body></html>".byteInputStream())
        }
        assertTrue(TrackFiles.read("""<gpx version="1.1"><trk><trkseg><trkpt lat="46" lon="7"/></trkseg></trk></gpx>""".byteInputStream()).tracks.isNotEmpty())
    }
}
