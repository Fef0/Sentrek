package app.sentrek.geo.gpx

import app.sentrek.geo.LatLon
import app.sentrek.geo.track.TrackPoint
import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler
import java.io.InputStream
import java.io.Writer
import java.util.Locale
import javax.xml.parsers.SAXParserFactory

/**
 * KML reading (Google Earth, My Maps, many route planners). Lines (`LineString`, `gx:Track`)
 * become tracks, `Point` placemarks become waypoints; styles and everything else are ignored.
 */
object Kml {
    /** A track as KML: one placemark with a line per segment, plus one per waypoint. */
    fun write(out: Writer, name: String, segments: List<List<TrackPoint>>, waypoints: List<GpxWaypoint> = emptyList()) {
        out.write("""<?xml version="1.0" encoding="UTF-8"?>""")
        out.write("\n<kml xmlns=\"http://www.opengis.net/kml/2.2\">\n<Document>\n")
        out.write("  <name>${escape(name)}</name>\n")
        out.write("  <Style id=\"route\"><LineStyle><color>ff2f6be8</color><width>4</width></LineStyle></Style>\n")
        for (w in waypoints) {
            out.write("  <Placemark>")
            w.name?.let { out.write("<name>${escape(it)}</name>") }
            w.note?.takeIf { it.isNotEmpty() }?.let { out.write("<description>${escape(it)}</description>") }
            out.write("<Point><coordinates>${coordinates(w.position)}</coordinates></Point></Placemark>\n")
        }
        out.write("  <Placemark>\n    <name>${escape(name)}</name>\n    <styleUrl>#route</styleUrl>\n    <MultiGeometry>\n")
        for (segment in segments.filter { it.size >= 2 }) {
            out.write("      <LineString><tessellate>1</tessellate><coordinates>\n")
            for (p in segment) out.write("        ${coordinates(p.position)}\n")
            out.write("      </coordinates></LineString>\n")
        }
        out.write("    </MultiGeometry>\n  </Placemark>\n</Document>\n</kml>\n")
        out.flush()
    }

    /** `lon,lat[,ele]`, as KML wants them. */
    private fun coordinates(p: LatLon): String {
        val ele = p.ele?.let { "," + String.format(Locale.ROOT, "%.1f", it) }.orEmpty()
        return Gpx.coordinate(p.lon) + "," + Gpx.coordinate(p.lat) + ele
    }

    private fun escape(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

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
        private var placemarkNote: String? = null
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
                "Placemark" -> { placemarkName = null; placemarkNote = null; lines.clear(); points.clear() }
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
                "description" -> if (parent == "Placemark") placemarkNote = value.ifEmpty { null }
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
                    points.forEach { waypoints += GpxWaypoint(placemarkName, it, placemarkNote) }
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
