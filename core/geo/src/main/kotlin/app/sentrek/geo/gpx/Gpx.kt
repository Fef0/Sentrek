package app.sentrek.geo.gpx

import app.sentrek.geo.LatLon
import app.sentrek.geo.track.TrackPoint
import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler
import java.io.InputStream
import java.io.Writer
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import javax.xml.parsers.SAXParserFactory

/** Contents of a GPX file. Routes (`rte`) are read as one-segment tracks. */
data class GpxFile(
    val name: String?,
    val tracks: List<GpxTrack>,
    val waypoints: List<GpxWaypoint>,
)

data class GpxTrack(val name: String?, val segments: List<List<TrackPoint>>)

/** [type]: GPX `<type>`, or `<sym>` when there is no type. [time] 0 when unknown. */
data class GpxWaypoint(
    val name: String?,
    val position: LatLon,
    val note: String? = null,
    val type: String? = null,
    val time: Long = 0,
)

/**
 * GPX 1.1 reading and writing. SAX is streaming and exists both on Android and the JVM,
 * so the same code is unit-tested here and runs on old phones without loading whole files.
 */
object Gpx {
    fun write(
        out: Writer,
        name: String,
        segments: List<List<TrackPoint>>,
        creator: String,
        waypoints: List<GpxWaypoint> = emptyList(),
    ) {
        out.write("""<?xml version="1.0" encoding="UTF-8"?>""")
        out.write("\n")
        out.write("""<gpx version="1.1" creator="${escape(creator)}" xmlns="http://www.topografix.com/GPX/1/1">""")
        out.write("\n")
        out.write("  <metadata><name>${escape(name)}</name></metadata>\n")
        for (w in waypoints) {
            val p = w.position
            out.write("""  <wpt lat="${coordinate(p.lat)}" lon="${coordinate(p.lon)}">""")
            p.ele?.let { out.write("<ele>${"%.1f".format(Locale.ROOT, it)}</ele>") }
            if (w.time > 0) out.write("<time>${isoTime(w.time)}</time>")
            w.name?.let { out.write("<name>${escape(it)}</name>") }
            w.note?.takeIf { it.isNotEmpty() }?.let { out.write("<desc>${escape(it)}</desc>") }
            w.type?.let { out.write("<type>${escape(it)}</type>") }
            out.write("</wpt>\n")
        }
        // No segments: a file of waypoints only (saved places).
        if (segments.isEmpty()) {
            out.write("</gpx>\n")
            out.flush()
            return
        }
        out.write("  <trk>\n    <name>${escape(name)}</name>\n")
        for (segment in segments) {
            out.write("    <trkseg>\n")
            for (point in segment) {
                val p = point.position
                out.write("""      <trkpt lat="${coordinate(p.lat)}" lon="${coordinate(p.lon)}">""")
                p.ele?.let { out.write("<ele>${"%.1f".format(Locale.ROOT, it)}</ele>") }
                if (point.time > 0) out.write("<time>${isoTime(point.time)}</time>")
                out.write("</trkpt>\n")
            }
            out.write("    </trkseg>\n")
        }
        out.write("  </trk>\n</gpx>\n")
        out.flush()
    }

    fun read(input: InputStream): GpxFile {
        val handler = Handler()
        SAXParserFactory.newInstance().apply { isNamespaceAware = true }.newSAXParser().parse(input, handler)
        return GpxFile(handler.fileName, handler.tracks, handler.waypoints)
    }

    private class Handler : DefaultHandler() {
        var fileName: String? = null
        val tracks = mutableListOf<GpxTrack>()
        val waypoints = mutableListOf<GpxWaypoint>()

        private val path = ArrayDeque<String>()
        private val text = StringBuilder()
        private var trackName: String? = null
        private var segments = mutableListOf<List<TrackPoint>>()
        private var segment = mutableListOf<TrackPoint>()
        private var lat = Double.NaN
        private var lon = Double.NaN
        private var ele: Double? = null
        private var time = 0L
        private var pointName: String? = null
        private var pointNote: String? = null
        private var pointType: String? = null
        private var pointSymbol: String? = null

        override fun startElement(uri: String?, localName: String?, qName: String, attributes: Attributes) {
            val name = localName?.takeIf { it.isNotEmpty() } ?: qName.substringAfter(':')
            path.addLast(name)
            text.setLength(0)
            when (name) {
                "trk", "rte" -> { trackName = null; segments = mutableListOf(); segment = mutableListOf() }
                "trkseg" -> segment = mutableListOf()
                "trkpt", "rtept", "wpt" -> {
                    lat = attributes.getValue("lat")?.toDoubleOrNull() ?: Double.NaN
                    lon = attributes.getValue("lon")?.toDoubleOrNull() ?: Double.NaN
                    ele = null
                    time = 0L
                    pointName = null
                    pointNote = null
                    pointType = null
                    pointSymbol = null
                }
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
                "ele" -> ele = value.toDoubleOrNull()
                "time" -> if (parent in POINTS) time = parseTime(value)
                "name" -> when (parent) {
                    "trk", "rte" -> trackName = value
                    "metadata" -> fileName = value
                    in POINTS -> pointName = value
                }
                "trkpt", "rtept" -> point()?.let { segment += it }
                // Most apps put the note in <desc>, some in <cmt>.
                "desc" -> if (parent == "wpt") pointNote = value.ifEmpty { pointNote }
                "cmt" -> if (parent == "wpt" && pointNote == null) pointNote = value.ifEmpty { null }
                "type" -> if (parent == "wpt") pointType = value.ifEmpty { null }
                "sym" -> if (parent == "wpt") pointSymbol = value.ifEmpty { null }
                "wpt" -> point()?.let {
                    waypoints += GpxWaypoint(pointName, it.position, pointNote, pointType ?: pointSymbol, it.time)
                }
                "trkseg" -> if (segment.isNotEmpty()) segments += segment
                "rte" -> { if (segment.isNotEmpty()) segments += segment; finishTrack() }
                "trk" -> finishTrack()
            }
        }

        private fun point(): TrackPoint? {
            if (lat.isNaN() || lon.isNaN()) return null
            val position = runCatching { LatLon(lat, lon, ele) }.getOrNull() ?: return null
            return TrackPoint(position, time)
        }

        private fun finishTrack() {
            if (segments.isNotEmpty()) tracks += GpxTrack(trackName, segments)
        }

        companion object {
            val POINTS = setOf("trkpt", "rtept", "wpt")
        }
    }

    private val ISO = Regex("""(\d{4})-(\d\d)-(\d\d)T(\d\d):(\d\d):(\d\d)(\.\d+)?(Z|[+-]\d\d:?\d\d)?""")

    /** ISO 8601 as used in GPX; 0 when unparseable. java.time needs API 26, we support 24. */
    internal fun parseTime(s: String): Long {
        val m = ISO.matchEntire(s) ?: return 0L
        val g = m.groupValues
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"), Locale.ROOT).apply {
            clear()
            set(g[1].toInt(), g[2].toInt() - 1, g[3].toInt(), g[4].toInt(), g[5].toInt(), g[6].toInt())
        }
        var millis = cal.timeInMillis + (g[7].takeIf { it.isNotEmpty() }?.let { (("0" + it).toDouble() * 1000).toLong() } ?: 0L)
        val zone = g[8]
        if (zone.isNotEmpty() && zone != "Z") {
            val digits = zone.substring(1).replace(":", "")
            val offset = (digits.substring(0, 2).toInt() * 60 + digits.substring(2).toInt()) * 60_000L
            millis -= if (zone[0] == '+') offset else -offset
        }
        return millis
    }

    internal fun isoTime(millis: Long): String {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"), Locale.ROOT).apply { timeInMillis = millis }
        return String.format(
            Locale.ROOT, "%04d-%02d-%02dT%02d:%02d:%02dZ",
            cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH),
            cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), cal.get(Calendar.SECOND),
        )
    }

    /** Seven decimals ≈ 1 cm, without binary noise like 7.6899999999999995. */
    fun coordinate(degrees: Double): String =
        java.math.BigDecimal(degrees).setScale(7, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()

    private fun escape(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
