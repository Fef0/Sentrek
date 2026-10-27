package app.sentrek.geo.track

import app.sentrek.geo.LatLon
import app.sentrek.geo.gpx.Gpx
import java.io.Reader
import java.io.Writer

/**
 * Append-only text log of the track being recorded, one line per point, so a crash or a
 * killed process loses at most the last line.
 *
 * - `lat,lon,ele,time` is a point, with `-` for no elevation;
 * - `SEG` starts a new segment;
 * - `PAUSE time` / `RESUME time` mark pauses.
 */
object TrackJournal {
    private const val SEGMENT = "SEG"
    private const val PAUSE = "PAUSE"
    private const val RESUME = "RESUME"

    /** What a journal holds: the points, and how long the recording was paused. */
    data class Contents(
        val segments: List<List<TrackPoint>>,
        /** Total of finished pauses, in milliseconds. */
        val pausedMillis: Long,
        /** Start of the current pause, or null when recording. */
        val pausedSince: Long?,
    )

    fun appendSegment(out: Writer) {
        out.write(SEGMENT)
        out.write("\n")
    }

    fun appendPause(out: Writer, time: Long) = out.write("$PAUSE $time\n")

    fun appendResume(out: Writer, time: Long) = out.write("$RESUME $time\n")

    fun append(out: Writer, point: TrackPoint) {
        val p = point.position
        val ele = p.ele?.let { "%.1f".format(java.util.Locale.ROOT, it) } ?: "-"
        out.write("${Gpx.coordinate(p.lat)},${Gpx.coordinate(p.lon)},$ele,${point.time}\n")
    }

    /** Reads every complete line; a torn last line (crash mid-write) is skipped. */
    fun read(input: Reader): Contents {
        val segments = mutableListOf<MutableList<TrackPoint>>()
        var pausedMillis = 0L
        var pausedSince: Long? = null
        input.buffered().forEachLine { line ->
            when {
                line == SEGMENT -> segments += mutableListOf<TrackPoint>()
                line.startsWith("$PAUSE ") -> line.substringAfter(' ').toLongOrNull()?.let { pausedSince = it }
                line.startsWith("$RESUME ") -> {
                    val time = line.substringAfter(' ').toLongOrNull()
                    val since = pausedSince
                    if (time != null && since != null) pausedMillis += (time - since).coerceAtLeast(0)
                    pausedSince = null
                }
                else -> point(line)?.let {
                    if (segments.isEmpty()) segments += mutableListOf<TrackPoint>()
                    segments.last() += it
                }
            }
        }
        return Contents(segments.filter { it.isNotEmpty() }, pausedMillis, pausedSince)
    }

    private fun point(line: String): TrackPoint? {
        val parts = line.split(',')
        if (parts.size != 4) return null
        val lat = parts[0].toDoubleOrNull() ?: return null
        val lon = parts[1].toDoubleOrNull() ?: return null
        val time = parts[3].toLongOrNull() ?: return null
        val position = runCatching { LatLon(lat, lon, parts[2].toDoubleOrNull()) }.getOrNull() ?: return null
        return TrackPoint(position, time)
    }
}
