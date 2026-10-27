package app.sentrek.geo.track

import app.sentrek.geo.LatLon
import app.sentrek.geo.gpx.Gpx
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.StringReader
import java.io.StringWriter

class TrackTest {
    private fun pt(lat: Double, lon: Double, ele: Double?, time: Long) = TrackPoint(LatLon(lat, lon, ele), time)

    @Test
    fun `climb ignores gps jitter below the threshold`() {
        val stats = TrackStats()
        // Jitter of ±5 m around 1000, then a real 50 m climb, then 20 m down.
        listOf(1000.0, 1005.0, 996.0, 1004.0, 1020.0, 1050.0, 1040.0, 1030.0).forEachIndexed { i, ele ->
            stats.add(pt(46.0 + i * 0.0001, 7.0, ele, 1_000L * (i + 1)))
        }
        assertEquals(50.0, stats.ascent, 0.01)
        assertEquals(20.0, stats.descent, 0.01)
        assertEquals(1_000L, stats.startTime)
        assertEquals(8_000L, stats.endTime)
        assertEquals(8, stats.pointCount)
    }

    @Test
    fun `segments are not joined`() {
        val a = listOf(pt(46.0, 7.0, null, 1), pt(46.001, 7.0, null, 2))
        val b = listOf(pt(47.0, 7.0, null, 3), pt(47.001, 7.0, null, 4))
        // Two ~111 m segments, 111 km apart.
        val stats = TrackStats.of(listOf(a, b))
        assertEquals(222.4, stats.distance, 0.5)
        // 1 ms in each segment; the gap between them (a pause) doesn't count.
        assertEquals(2L, stats.activeMillis)
    }

    @Test
    fun `journal survives a torn last line`() {
        val out = StringWriter()
        TrackJournal.appendSegment(out)
        TrackJournal.append(out, pt(46.5, 7.25, 1234.56, 1000))
        TrackJournal.append(out, pt(46.6, 7.35, null, 2000))
        TrackJournal.appendSegment(out)
        TrackJournal.append(out, pt(46.7, 7.45, 1500.0, 3000))
        val torn = out.toString() + "46.8,7.5"

        val segments = TrackJournal.read(StringReader(torn)).segments
        assertEquals(2, segments.size)
        assertEquals(1234.6, segments[0][0].position.ele!!, 1e-9)
        assertNull(segments[0][1].position.ele)
        assertEquals(listOf(3000L), segments[1].map { it.time })
    }

    @Test
    fun `journal keeps pauses`() {
        val out = StringWriter()
        TrackJournal.appendSegment(out)
        TrackJournal.append(out, pt(46.5, 7.25, null, 1_000))
        TrackJournal.appendPause(out, 10_000)
        TrackJournal.appendResume(out, 70_000)
        TrackJournal.appendSegment(out)
        TrackJournal.append(out, pt(46.6, 7.35, null, 80_000))
        TrackJournal.appendPause(out, 90_000)

        val journal = TrackJournal.read(StringReader(out.toString()))
        assertEquals(2, journal.segments.size)
        assertEquals(60_000L, journal.pausedMillis)
        assertEquals(90_000L, journal.pausedSince)
    }

    @Test
    fun `gpx round trip keeps segments, elevation and time`() {
        val segments = listOf(
            listOf(pt(45.97633, 7.65859, 3260.0, 1_761_566_400_000), pt(45.977, 7.659, 3270.5, 1_761_566_460_000)),
            listOf(pt(45.98, 7.66, null, 0)),
        )
        val out = StringWriter()
        Gpx.write(out, "Hörnli & <back>", segments, "Sentrek test")
        val gpx = Gpx.read(out.toString().byteInputStream())

        assertEquals("Hörnli & <back>", gpx.name)
        val track = gpx.tracks.single()
        assertEquals("Hörnli & <back>", track.name)
        assertEquals(segments, track.segments)
    }

    @Test
    fun `coordinates are written without binary noise`() {
        assertEquals("7.69", Gpx.coordinate(7.6899999999999995))
        assertEquals("45.9912346", Gpx.coordinate(45.99123456))
        assertEquals("-0.5", Gpx.coordinate(-0.5))
    }

    @Test
    fun `reads routes, waypoints and time zones from other apps`() {
        val xml = """<?xml version="1.0"?>
            <gpx xmlns="http://www.topografix.com/GPX/1/1" version="1.1" creator="x">
              <wpt lat="46.1" lon="7.1"><name>Spring</name></wpt>
              <rte><name>Route</name>
                <rtept lat="46.0" lon="7.0"><time>2026-10-27T12:00:00.500+02:00</time></rtept>
                <rtept lat="46.01" lon="7.01"/>
              </rte>
            </gpx>"""
        val gpx = Gpx.read(xml.byteInputStream())
        assertEquals("Spring", gpx.waypoints.single().name)
        val route = gpx.tracks.single()
        assertEquals("Route", route.name)
        assertEquals(2, route.segments.single().size)
        assertEquals(Gpx.parseTime("2026-10-27T10:00:00.5Z"), route.segments.single()[0].time)
        assertEquals("2026-10-27T10:00:00Z", Gpx.isoTime(route.segments.single()[0].time))
    }
}
