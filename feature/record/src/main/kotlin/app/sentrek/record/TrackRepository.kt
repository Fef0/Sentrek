package app.sentrek.record

import android.content.Context
import android.net.Uri
import android.util.Log
import app.sentrek.geo.BoundingBox
import app.sentrek.geo.LatLon
import app.sentrek.geo.gpx.Gpx
import app.sentrek.geo.track.TrackPoint
import app.sentrek.geo.track.TrackStats
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/** A saved track: `<id>.gpx` plus a small `<id>.json` summary, so the list opens without parsing GPX. */
data class SavedTrack(
    val id: String,
    val name: String,
    val startTime: Long,
    val endTime: Long,
    val distance: Double,
    val ascent: Double,
    val descent: Double,
    /** Moving time, without pauses. */
    val duration: Long,
)

/** Saved tracks, and which of them are drawn on the map. */
@Singleton
class TrackRepository @Inject constructor(@ApplicationContext private val context: Context) {
    private val dir = File(context.filesDir, "tracks").apply { mkdirs() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _tracks = MutableStateFlow<List<SavedTrack>>(emptyList())
    val tracks: StateFlow<List<SavedTrack>> = _tracks.asStateFlow()

    /** Tracks shown on the map: id → segments. */
    private val _shown = MutableStateFlow<Map<String, List<List<LatLon>>>>(emptyMap())
    val shown: StateFlow<Map<String, List<List<LatLon>>>> = _shown.asStateFlow()

    init {
        scope.launch {
            _tracks.value = withContext(Dispatchers.IO) {
                dir.listFiles { f -> f.name.endsWith(SUMMARY) }.orEmpty()
                    .mapNotNull { runCatching { readSummary(it) }.getOrNull() }
                    .sortedByDescending { it.startTime }
            }
        }
    }

    fun gpxFile(track: SavedTrack) = File(dir, track.id + GPX)

    /** [name]: blank or null uses [defaultName]. */
    suspend fun save(segments: List<List<TrackPoint>>, name: String?): SavedTrack = withContext(Dispatchers.IO) {
        val stats = TrackStats.of(segments)
        val start = stats.startTime.takeIf { it > 0 } ?: System.currentTimeMillis()
        val id = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date(start))
        val title = name?.trim()?.takeIf { it.isNotEmpty() } ?: defaultName(start)
        val track = SavedTrack(
            id, title, start, stats.endTime.coerceAtLeast(start), stats.distance, stats.ascent, stats.descent, stats.activeMillis,
        )
        write(track, segments)
        _tracks.update { (listOf(track) + it.filter { t -> t.id != id }).sortedByDescending { t -> t.startTime } }
        track
    }

    /** Renames the track, in the list and inside its GPX file. */
    suspend fun rename(track: SavedTrack, name: String) {
        val title = name.trim().takeIf { it.isNotEmpty() && it != track.name } ?: return
        val renamed = track.copy(name = title)
        val ok = withContext(Dispatchers.IO) {
            runCatching {
                val segments = gpxFile(track).inputStream().buffered().use { Gpx.read(it) }.tracks.flatMap { it.segments }
                write(renamed, segments)
            }.onFailure { Log.w(TAG, "Cannot rename ${track.id}", it) }.isSuccess
        }
        if (ok) _tracks.update { all -> all.map { if (it.id == track.id) renamed else it } }
    }

    /** Date and time the track started, e.g. "Oct 27, 2026 2:55 PM". */
    fun defaultName(start: Long): String =
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(start))

    private fun write(track: SavedTrack, segments: List<List<TrackPoint>>) {
        // Write to a temp file first: a half-written GPX must never replace a good one.
        val gpxTmp = File(dir, "${track.id}$GPX.tmp")
        gpxTmp.bufferedWriter().use { Gpx.write(it, track.name, segments, CREATOR) }
        gpxTmp.renameTo(File(dir, track.id + GPX))
        File(dir, track.id + SUMMARY).writeText(summaryJson(track))
    }

    suspend fun delete(track: SavedTrack) {
        hide(track)
        withContext(Dispatchers.IO) {
            gpxFile(track).delete()
            File(dir, track.id + SUMMARY).delete()
        }
        _tracks.update { it - track }
    }

    /** Draws [track] on the map; returns its bounds to fly to. */
    suspend fun show(track: SavedTrack): BoundingBox? {
        val segments = withContext(Dispatchers.IO) {
            runCatching {
                gpxFile(track).inputStream().buffered().use { Gpx.read(it) }
                    .tracks.flatMap { t -> t.segments.map { s -> s.map(TrackPoint::position) } }
            }.onFailure { Log.w(TAG, "Cannot read ${track.id}", it) }.getOrNull()
        } ?: return null
        _shown.update { it + (track.id to segments) }
        return bounds(segments.flatten())
    }

    fun hide(track: SavedTrack) = _shown.update { it - track.id }

    /**
     * A copy of the GPX named after the track, for the share sheet: the receiving app shows
     * the file name, and the stored file is named by date. Older copies are removed.
     */
    suspend fun shareCopy(track: SavedTrack): File? = withContext(Dispatchers.IO) {
        runCatching {
            val shareDir = File(context.cacheDir, "share").apply { deleteRecursively(); mkdirs() }
            File(shareDir, fileName(track)).also { gpxFile(track).copyTo(it) }
        }.onFailure { Log.w(TAG, "Cannot prepare ${track.id} for sharing", it) }.getOrNull()
    }

    /** Copies the GPX to a user-chosen document (Storage Access Framework). */
    suspend fun export(track: SavedTrack, target: Uri): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.openOutputStream(target)?.use { out ->
                gpxFile(track).inputStream().use { it.copyTo(out) }
            } ?: error("No output stream")
        }.onFailure { Log.w(TAG, "Export of ${track.id} failed", it) }.isSuccess
    }

    private fun summaryJson(t: SavedTrack) = JSONObject()
        .put("name", t.name)
        .put("start", t.startTime)
        .put("end", t.endTime)
        .put("duration", t.duration)
        .put("distance", t.distance)
        .put("ascent", t.ascent)
        .put("descent", t.descent)
        .toString()

    private fun readSummary(file: File): SavedTrack {
        val json = JSONObject(file.readText())
        val start = json.getLong("start")
        val end = json.getLong("end")
        return SavedTrack(
            id = file.name.removeSuffix(SUMMARY),
            name = json.getString("name"),
            startTime = start,
            endTime = end,
            distance = json.getDouble("distance"),
            ascent = json.getDouble("ascent"),
            descent = json.getDouble("descent"),
            // Summaries written before pauses existed have no "duration".
            duration = json.optLong("duration", (end - start).coerceAtLeast(0)),
        )
    }

    companion object {
        private const val TAG = "TrackRepository"

        /** "<name>.gpx", without characters that file systems reject (dates contain some). */
        fun fileName(track: SavedTrack): String =
            track.name.replace(Regex("""[\\/:*?"<>|,]+"""), "-").trim('-', ' ').ifEmpty { track.id } + ".gpx"
        private const val GPX = ".gpx"
        private const val SUMMARY = ".json"
        private const val CREATOR = "Sentrek"

        fun bounds(points: List<LatLon>): BoundingBox? {
            if (points.isEmpty()) return null
            return BoundingBox(points.minOf { it.lat }, points.minOf { it.lon }, points.maxOf { it.lat }, points.maxOf { it.lon })
        }
    }
}
