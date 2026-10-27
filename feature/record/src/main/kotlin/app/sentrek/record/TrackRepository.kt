package app.sentrek.record

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import app.sentrek.feature.record.R
import app.sentrek.geo.BoundingBox
import app.sentrek.geo.Geo
import app.sentrek.geo.LatLon
import app.sentrek.geo.gpx.Gpx
import app.sentrek.geo.gpx.GpxFile
import app.sentrek.geo.gpx.GpxWaypoint
import app.sentrek.geo.gpx.Kml
import app.sentrek.geo.plan.PlanLeg
import app.sentrek.geo.plan.PlanPoint
import app.sentrek.geo.plan.SavedPlan
import app.sentrek.geo.gpx.TrackFiles
import app.sentrek.geo.route.Route
import app.sentrek.geo.track.TrackPoint
import app.sentrek.geo.track.TrackStats
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
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
    /** Moving time, without pauses; 0 for planned routes without times. */
    val duration: Long,
    /** Imported from a file rather than recorded here. */
    val imported: Boolean = false,
    /** Line colour on the map (ARGB), one of [TrackColors.palette]. */
    val color: Int = TrackColors.palette.first(),
)

/** Track colours: dark and saturated, so they read on the light map and next to the trail colours. */
object TrackColors {
    val palette: List<Int> = listOf(
        0xFF7B1FA2, // purple
        0xFFC2185B, // magenta
        0xFF00838F, // teal
        0xFF2E7D32, // green
        0xFF3949AB, // indigo
        0xFF6D4C41, // brown
        0xFF212121, // black
        0xFF9E9D24, // olive
    ).map { it.toInt() }
}

/** A track as drawn on the map. */
data class TrackShape(val segments: List<List<LatLon>>, val waypoints: List<GpxWaypoint>)

sealed interface ImportResult {
    /** Area to show on the map after the import, if anything was imported. */
    val bounds: BoundingBox? get() = null

    data class Imported(val track: SavedTrack, override val bounds: BoundingBox?) : ImportResult
    /** A file with waypoints but no track: they became places. */
    data class ImportedPlaces(val count: Int, override val bounds: BoundingBox?) : ImportResult
    /** Several files at once: [failed] couldn't be read. */
    data class ImportedMany(
        val tracks: List<SavedTrack>,
        val places: Int,
        val failed: Int,
        override val bounds: BoundingBox?,
    ) : ImportResult
    data object Empty : ImportResult
    data object UnknownFormat : ImportResult
    data object Failed : ImportResult
}

/** Saved tracks, and which of them are drawn on the map. */
@Singleton
class TrackRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val places: PlaceRepository,
    private val elevation: ElevationRepository,
) {
    private val dir = File(context.filesDir, "tracks").apply { mkdirs() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _tracks = MutableStateFlow<List<SavedTrack>>(emptyList())
    val tracks: StateFlow<List<SavedTrack>> = _tracks.asStateFlow()

    /** Tracks shown on the map, by id. Kept on disk, so they're still there after a restart. */
    private val _shown = MutableStateFlow<Map<String, TrackShape>>(emptyMap())
    val shown: StateFlow<Map<String, TrackShape>> = _shown.asStateFlow()
    private val shownFile = File(context.filesDir, "shown.txt")

    /** Elevation profiles already read, by track id. */
    private val profiles = HashMap<String, List<Pair<Double, Double>>>()

    /** One write at a time, in order: parallel writes could finish out of order and save an old list. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val fileWrites = Dispatchers.IO.limitedParallelism(1)

    init {
        scope.launch {
            _tracks.value = withContext(Dispatchers.IO) {
                dir.listFiles { f -> f.name.endsWith(SUMMARY) }.orEmpty()
                    .mapNotNull { runCatching { readSummary(it) }.getOrNull() }
                    .sortedByDescending { it.startTime }
            }
            val ids = withContext(Dispatchers.IO) { runCatching { shownFile.readLines() }.getOrDefault(emptyList()) }
            ids.mapNotNull(::find).forEach { show(it) }
        }
    }

    fun gpxFile(track: SavedTrack) = File(dir, track.id + GPX)

    /**
     * [name]: blank or null uses [defaultName]. [smoothElevation]: heights come from the terrain
     * model (a planned route), not GPS, so small climbs are real. [plan]: the planner's points
     * and legs, kept to edit the route again.
     */
    suspend fun save(
        segments: List<List<TrackPoint>>,
        name: String?,
        waypoints: List<GpxWaypoint> = emptyList(),
        imported: Boolean = false,
        smoothElevation: Boolean = false,
        plan: SavedPlan? = null,
    ): SavedTrack = withContext(Dispatchers.IO) {
        val threshold = if (smoothElevation) TrackStats.SMOOTH_CLIMB_THRESHOLD_M else TrackStats.CLIMB_THRESHOLD_M
        val stats = TrackStats.of(segments, threshold)
        val start = stats.startTime.takeIf { it > 0 } ?: System.currentTimeMillis()
        val id = uniqueId(start)
        val title = name?.trim()?.takeIf { it.isNotEmpty() } ?: defaultName(start)
        // Each new track takes the next colour, so tracks shown together look different.
        val color = TrackColors.palette[_tracks.value.size % TrackColors.palette.size]
        val track = SavedTrack(
            id, title, start, stats.endTime.coerceAtLeast(start), stats.distance, stats.ascent, stats.descent,
            stats.activeMillis, imported, color,
        )
        write(track, segments, waypoints)
        plan?.let { writePlan(track, it) }
        _tracks.update { (listOf(track) + it).sortedByDescending { t -> t.startTime } }
        track
    }

    /** Imports several files; they're all shown, and the bounds cover them all. */
    suspend fun importAll(uris: List<Uri>): ImportResult {
        if (uris.size == 1) return import(uris.single())
        val results = uris.map { import(it) }
        val imported = results.filterIsInstance<ImportResult.Imported>()
        val placeFiles = results.filterIsInstance<ImportResult.ImportedPlaces>()
        val succeeded = imported.size + placeFiles.size
        if (succeeded == 0) return results.firstOrNull() ?: ImportResult.Failed
        val corners = (imported + placeFiles).mapNotNull { it.bounds }
            .flatMap { listOf(LatLon(it.south, it.west), LatLon(it.north, it.east)) }
        return ImportResult.ImportedMany(
            imported.map { it.track }, placeFiles.sumOf { it.count }, results.size - succeeded, bounds(corners),
        )
    }

    /**
     * Imports a GPX, KML or KMZ file; all its tracks become one track, with its waypoints.
     * A file with waypoints only (a list of places) adds them to the saved places.
     */
    suspend fun import(uri: Uri): ImportResult {
        val result = withContext(Dispatchers.IO) {
            runCatching {
                val file = context.contentResolver.openInputStream(uri)?.use { TrackFiles.read(it.buffered()) }
                    ?: return@runCatching ImportResult.Failed
                val segments = file.tracks.flatMap { it.segments }.filter { it.size >= 2 }
                val name = file.name?.takeIf { it.isNotBlank() }
                    ?: file.tracks.firstNotNullOfOrNull { it.name?.takeIf(String::isNotBlank) }
                    ?: displayName(uri)
                if (segments.isEmpty()) {
                    if (file.waypoints.isEmpty()) return@runCatching ImportResult.Empty
                    val fallback = name ?: context.getString(R.string.places_unnamed)
                    val added = withContext(Dispatchers.Main) { places.addAll(file.waypoints, fallback) }
                    return@runCatching ImportResult.ImportedPlaces(added.size, bounds(added.map { it.position }))
                }
                // Files from some planners have no heights: take them from the terrain while we can.
                val filled = if (segments.flatten().none { it.position.ele != null }) withTerrain(segments) else null
                val track = save(filled ?: segments, name, file.waypoints, imported = true, smoothElevation = filled != null)
                ImportResult.Imported(track, bounds(segments.flatten().map(TrackPoint::position)))
            }.getOrElse {
                Log.w(TAG, "Import of $uri failed", it)
                if (it is TrackFiles.UnsupportedFormatException) ImportResult.UnknownFormat else ImportResult.Failed
            }
        }
        if (result is ImportResult.Imported) show(result.track)
        return result
    }

    /** Renames the track, in the list and inside its GPX file. */
    suspend fun rename(track: SavedTrack, name: String) {
        val title = name.trim().takeIf { it.isNotEmpty() && it != track.name } ?: return
        val renamed = track.copy(name = title)
        val ok = withContext(Dispatchers.IO) {
            runCatching {
                val gpx = read(track)
                write(renamed, gpx.tracks.flatMap { it.segments }, gpx.waypoints)
            }.onFailure { Log.w(TAG, "Cannot rename ${track.id}", it) }.isSuccess
        }
        if (ok) _tracks.update { all -> all.map { if (it.id == track.id) renamed else it } }
    }

    suspend fun setColor(track: SavedTrack, color: Int) {
        val recolored = track.copy(color = color)
        withContext(Dispatchers.IO) { File(dir, track.id + SUMMARY).writeText(summaryJson(recolored)) }
        _tracks.update { all -> all.map { if (it.id == track.id) recolored else it } }
    }

    /** Date and time the track started, e.g. "Oct 27, 2026 2:55 PM". */
    fun defaultName(start: Long): String =
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(start))

    suspend fun delete(track: SavedTrack) {
        hide(track)
        profiles -= track.id
        withContext(Dispatchers.IO) {
            gpxFile(track).delete()
            File(dir, track.id + SUMMARY).delete()
            File(dir, track.id + PLAN).delete()
        }
        _tracks.update { it - track }
    }

    /**
     * Puts an edited route in place of [track]: same id, name and colour, new line and numbers.
     * Its waypoints stay. Returns the updated track, or null when it couldn't be written.
     */
    suspend fun replace(track: SavedTrack, segments: List<List<TrackPoint>>, plan: SavedPlan): SavedTrack? {
        val updated = withContext(Dispatchers.IO) {
            runCatching {
                val waypoints = runCatching { read(track).waypoints }.getOrDefault(emptyList())
                val stats = TrackStats.of(segments, TrackStats.SMOOTH_CLIMB_THRESHOLD_M)
                val edited = track.copy(distance = stats.distance, ascent = stats.ascent, descent = stats.descent, duration = 0)
                write(edited, segments, waypoints)
                writePlan(edited, plan)
                edited
            }.onFailure { Log.w(TAG, "Cannot save edited ${track.id}", it) }.getOrNull()
        } ?: return null
        profiles -= track.id
        _tracks.update { all -> all.map { if (it.id == track.id) updated else it } }
        if (track.id in _shown.value) show(updated)
        return updated
    }

    /**
     * The route as the planner edits it: the plan saved with it, or one made from its line
     * (points where it bends). Null for recorded tracks and unreadable files.
     */
    suspend fun plan(track: SavedTrack): SavedPlan? {
        if (!track.imported) return null
        return withContext(Dispatchers.IO) {
            runCatching {
                File(dir, track.id + PLAN).takeIf { it.exists() }?.let { parsePlan(it.readText()) }
                    ?: SavedPlan.fromLine(read(track).tracks.flatMap { it.segments }.flatten().map(TrackPoint::position))
            }.onFailure { Log.w(TAG, "Cannot load plan of ${track.id}", it) }.getOrNull()
        }
    }

    private fun writePlan(track: SavedTrack, plan: SavedPlan) {
        val json = JSONObject()
            .put("points", JSONArray(plan.points.map { JSONArray(listOf(it.position.lat, it.position.lon, it.onTrails)) }))
            .put(
                "legs",
                JSONArray(
                    plan.legs.map { leg ->
                        JSONObject()
                            .put("t", leg.onTrails)
                            .put("s", leg.offPathStart)
                            .put("e", leg.offPathEnd)
                            .put("p", JSONArray(leg.points.map { JSONArray(listOf(it.lat, it.lon)) }))
                    },
                ),
            )
        File(dir, track.id + PLAN).writeText(json.toString())
    }

    private fun parsePlan(text: String): SavedPlan {
        val json = JSONObject(text)
        val points = json.getJSONArray("points").let { a ->
            (0 until a.length()).map { i ->
                val p = a.getJSONArray(i)
                PlanPoint(LatLon(p.getDouble(0), p.getDouble(1)), p.getBoolean(2))
            }
        }
        val legs = json.getJSONArray("legs").let { a ->
            (0 until a.length()).map { i ->
                val leg = a.getJSONObject(i)
                val p = leg.getJSONArray("p")
                PlanLeg(
                    points = (0 until p.length()).map { j -> p.getJSONArray(j).let { LatLon(it.getDouble(0), it.getDouble(1)) } },
                    onTrails = leg.getBoolean("t"),
                    offPathStart = leg.optBoolean("s"),
                    offPathEnd = leg.optBoolean("e"),
                )
            }
        }
        return SavedPlan(points, legs)
    }

    /** Draws [track] on the map; returns its bounds to fly to. */
    suspend fun show(track: SavedTrack): BoundingBox? {
        val shape = withContext(Dispatchers.IO) {
            runCatching {
                val gpx = read(track)
                TrackShape(gpx.tracks.flatMap { t -> t.segments.map { s -> s.map(TrackPoint::position) } }, gpx.waypoints)
            }.onFailure { Log.w(TAG, "Cannot read ${track.id}", it) }.getOrNull()
        } ?: return null
        _shown.update { it + (track.id to shape) }
        saveShown()
        return bounds(shape.segments.flatten() + shape.waypoints.map { it.position })
    }

    fun hide(track: SavedTrack) = hide(track.id)

    fun hide(trackId: String) {
        _shown.update { it - trackId }
        saveShown()
    }

    private fun saveShown() {
        val ids = _shown.value.keys.joinToString("\n")
        scope.launch(fileWrites) { runCatching { shownFile.writeText(ids) } }
    }

    /** The track as a route to follow (segments joined), or null when it can't be read. */
    suspend fun route(track: SavedTrack): Route? = withContext(Dispatchers.IO) {
        runCatching {
            val points = read(track).tracks.flatMap { it.segments }.flatten().map(TrackPoint::position)
            Route(points)
        }.onFailure { Log.w(TAG, "Cannot load route ${track.id}", it) }.getOrNull()
    }

    /**
     * Elevation against distance along [track], for its profile. Read from the track's own file,
     * so it works offline. A track without any heights gets them from the terrain once (online,
     * or from cached terrain), and they are kept in its file. Empty when there are none.
     */
    suspend fun profile(track: SavedTrack): List<Pair<Double, Double>> {
        profiles[track.id]?.let { return it }
        val gpx = withContext(Dispatchers.IO) { runCatching { read(track) }.getOrNull() } ?: return emptyList()
        var segments = gpx.tracks.flatMap { it.segments }
        if (segments.flatten().none { it.position.ele != null }) {
            segments = withTerrain(segments) ?: return emptyList()
            val stats = TrackStats.of(segments, TrackStats.SMOOTH_CLIMB_THRESHOLD_M)
            val updated = track.copy(ascent = stats.ascent, descent = stats.descent)
            val saved = withContext(Dispatchers.IO) {
                runCatching { write(updated, segments, gpx.waypoints) }
                    .onFailure { Log.w(TAG, "Cannot add heights to ${track.id}", it) }.isSuccess
            }
            if (saved) _tracks.update { all -> all.map { if (it.id == track.id) updated else it } }
        }
        return profileOf(segments).also { profiles[track.id] = it }
    }

    /** [segments] with terrain heights, or null if the terrain isn't there for all of them. */
    private suspend fun withTerrain(segments: List<List<TrackPoint>>): List<List<TrackPoint>>? {
        val heights = elevation.withElevation(segments.flatten().map { it.position })
        if (heights.isEmpty() || heights.any { it.ele == null }) return null
        var i = 0
        return segments.map { segment -> segment.map { TrackPoint(heights[i++], it.time) } }
    }

    fun find(id: String): SavedTrack? = _tracks.value.firstOrNull { it.id == id }

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

    /** Writes the track to a user-chosen document (Storage Access Framework), as GPX or KML. */
    suspend fun export(track: SavedTrack, target: Uri, kml: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.openOutputStream(target)?.use { out ->
                if (kml) {
                    val gpx = read(track)
                    out.bufferedWriter().let { Kml.write(it, track.name, gpx.tracks.flatMap { t -> t.segments }, gpx.waypoints) }
                } else {
                    gpxFile(track).inputStream().use { it.copyTo(out) }
                }
            } ?: error("No output stream")
        }.onFailure { Log.w(TAG, "Export of ${track.id} failed", it) }.isSuccess
    }

    private fun read(track: SavedTrack): GpxFile = gpxFile(track).inputStream().buffered().use { Gpx.read(it) }

    private fun write(track: SavedTrack, segments: List<List<TrackPoint>>, waypoints: List<GpxWaypoint>) {
        // Write to a temp file first: a half-written GPX must never replace a good one.
        val gpxTmp = File(dir, "${track.id}$GPX.tmp")
        gpxTmp.bufferedWriter().use { Gpx.write(it, track.name, segments, CREATOR, waypoints) }
        gpxTmp.renameTo(File(dir, track.id + GPX))
        File(dir, track.id + SUMMARY).writeText(summaryJson(track))
    }

    /** Ids are start times; the same file imported twice gets "-2", "-3"… */
    private fun uniqueId(start: Long): String {
        val base = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date(start))
        var id = base
        var n = 2
        while (File(dir, id + SUMMARY).exists()) id = "$base-${n++}"
        return id
    }

    private fun displayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0)?.substringBeforeLast('.') else null
        }
    }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/')?.substringBeforeLast('.')

    private fun summaryJson(t: SavedTrack) = JSONObject()
        .put("name", t.name)
        .put("start", t.startTime)
        .put("end", t.endTime)
        .put("duration", t.duration)
        .put("distance", t.distance)
        .put("ascent", t.ascent)
        .put("descent", t.descent)
        .put("imported", t.imported)
        .put("color", t.color)
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
            imported = json.optBoolean("imported", false),
            color = json.optInt("color", TrackColors.palette.first()),
        )
    }

    companion object {
        private const val TAG = "TrackRepository"
        private const val GPX = ".gpx"
        private const val SUMMARY = ".json"
        private const val PLAN = ".plan"
        private const val CREATOR = "Sentrek"

        /** "<name>.gpx" (or [extension]), without characters that file systems reject (dates contain some). */
        fun fileName(track: SavedTrack, extension: String = "gpx"): String =
            track.name.replace(Regex("""[\\/:*?"<>|,]+"""), "-").trim('-', ' ').ifEmpty { track.id } + "." + extension

        /** Points kept for a profile: enough for a phone-wide chart. */
        private const val PROFILE_POINTS = 400

        /** Distance along to height, for the points with a height; pauses between segments add no distance. */
        fun profileOf(segments: List<List<TrackPoint>>): List<Pair<Double, Double>> {
            val all = ArrayList<Pair<Double, Double>>()
            var along = 0.0
            for (segment in segments) {
                segment.forEachIndexed { i, p ->
                    if (i > 0) along += Geo.distance(segment[i - 1].position, p.position)
                    p.position.ele?.let { all += along to it }
                }
            }
            if (all.size <= PROFILE_POINTS) return all
            val step = all.size.toDouble() / PROFILE_POINTS
            return List(PROFILE_POINTS) { all[(it * step).toInt()] } + all.last()
        }

        fun bounds(points: List<LatLon>): BoundingBox? {
            if (points.isEmpty()) return null
            return BoundingBox(points.minOf { it.lat }, points.minOf { it.lon }, points.maxOf { it.lat }, points.maxOf { it.lon })
        }
    }
}
