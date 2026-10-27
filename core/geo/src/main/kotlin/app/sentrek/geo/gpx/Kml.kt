package app.sentrek.geo.gpx

import app.sentrek.geo.LatLon
import app.sentrek.geo.track.TrackPoint
import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler
import java.io.InputStream
import javax.xml.parsers.SAXParserFactory

/**
 * KML reading (Google Earth, My Maps, many route planners). Lines (`LineString`, `gx:Track`)
 * become tracks, `Point` placemarks become waypoints; styles and everything else are ignored.
 */
object Kml {
    fun read(input: InputStream): GpxFile {
        val handler = Handler()
        SAXParserFactory.newInstance().apply { isNamespaceAware = true }.newSAXParser().parse(input, handler)
        return GpxFile(handler.documentName, handler.tracks, handler.waypoints)
    }

    private class Handler : DefaultHandler() {
        var documentName: String? = null
        val tracks = mutableListOf<GpxTrack>()
        val waypoints = mutableListOf<GpxWaypoint>()

        private val path = ArrayDeque<String>()
        private val text = StringBuilder()
        private var placemarkName: String? = null
        private val lines = mutableListOf<List<TrackPoint>>()
        private val points = mutableListOf<LatLon>()

        // gx:Track: times and coordinates come in two parallel lists.
        private val whens = mutableListOf<Long>()
        private val coords = mutableListOf<LatLon>()

        override fun startElement(uri: String?, localName: String?, qName: String, attributes: Attributes) {
            val name = localName?.takeIf { it.isNotEmpty() } ?: qName.substringAfter(':')
            path.addLast(name)
            text.setLength(0)
            when (name) {
                "Placemark" -> { placemarkName = null; lines.clear(); points.clear() }
                "Track" -> { whens.clear(); coords.clear() }
            }
        }

        override fun characters(ch: CharArray, start: Int, length: Int) {
            text.append(ch, start, length)
        }

        override fun endElement(uri: String?, localName: String?, qName: String) {
            val name = path.removeLastOrNull() ?: return
            val parent = path.lastOrNull()
            val value = text.toString().trim()
            text.setLength(0)
            when (name) {
                "name" -> when (parent) {
                    "Placemark" -> placemarkName = value
                    "Document" -> if (documentName == null) documentName = value
                }
                "coordinates" -> {
                    val parsed = parseCoordinates(value)
                    when (parent) {
                        // Polygon outlines (LinearRing) are areas, not routes: skipped.
                        "LineString" -> if (parsed.size >= 2) lines += parsed.map { TrackPoint(it, 0) }
                        "Point" -> parsed.firstOrNull()?.let { points += it }
                    }
                }
                "when" -> whens += Gpx.parseTime(value)
                "coord" -> value.split(WHITESPACE).let { parts ->
                    if (parts.size >= 2) position(parts[0], parts[1], parts.getOrNull(2))?.let { coords += it }
                }
                "Track" -> if (coords.size >= 2) {
                    val times = if (whens.size == coords.size) whens else List(coords.size) { 0L }
                    lines += coords.zip(times) { p, t -> TrackPoint(p, t) }
                }
                "Placemark" -> {
                    if (lines.isNotEmpty()) tracks += GpxTrack(placemarkName, lines.toList())
                    points.forEach { waypoints += GpxWaypoint(placemarkName, it) }
                }
            }
        }

        /** `lon,lat[,alt]` tuples separated by whitespace. */
        private fun parseCoordinates(s: String): List<LatLon> = s.split(WHITESPACE).mapNotNull { tuple ->
            val parts = tuple.split(',')
            if (parts.size < 2) null else position(parts[0], parts[1], parts.getOrNull(2))
        }

        private fun position(lon: String, lat: String, alt: String?): LatLon? {
            val x = lon.toDoubleOrNull() ?: return null
            val y = lat.toDoubleOrNull() ?: return null
            // KML often has 0 altitude meaning "clamped to ground": treat it as unknown.
            val ele = alt?.toDoubleOrNull()?.takeIf { it != 0.0 }
            return runCatching { LatLon(y, x, ele) }.getOrNull()
        }

        companion object {
            val WHITESPACE = Regex("""\s+""")
        }
    }
}
