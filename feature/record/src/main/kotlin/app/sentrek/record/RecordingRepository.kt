package app.sentrek.record

import android.content.Context
import android.location.Location
import android.util.Log
import app.sentrek.geo.Geo
import app.sentrek.geo.LatLon
import app.sentrek.geo.track.TrackJournal
import app.sentrek.geo.track.TrackPoint
import app.sentrek.geo.track.TrackStats
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.FileWriter
import java.io.Writer
import javax.inject.Inject
import javax.inject.Singleton

/** The track being recorded, as the UI sees it. */
data class Recording(
    val startTime: Long,
    val distance: Double,
    val ascent: Double,
    val descent: Double,
    /** Total of finished pauses. */
    val pausedMillis: Long,
    /** Start of the current pause, or null when recording. */
    val pausedSince: Long?,
    /** Kept points, by segment, for drawing. */
    val segments: List<List<LatLon>>,
) {
    val isPaused: Boolean get() = pausedSince != null

    /** Time spent recording, without pauses. */
    fun activeMillis(now: Long): Long =
        (now - startTime - pausedMillis - (pausedSince?.let { now - it } ?: 0)).coerceAtLeast(0)
}

/**
 * Records the current track. Every kept point is appended to a journal file at once, so the
 * recording survives the app being killed: it resumes, in a new segment, on the next start.
 */
@Singleton
class RecordingRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val tracks: TrackRepository,
) {
    private val journal = File(File(context.filesDir, "recording").apply { mkdirs() }, "current.txt")

    private val segments = mutableListOf<MutableList<TrackPoint>>()
    private var stats = TrackStats()
    private var startTime = 0L
    private var pausedMillis = 0L
    private var pausedSince: Long? = null
    private var writer: Writer? = null

    /** The next point starts a new segment: at start, after a pause, after an app restart. */
    private var newSegment = true

    private val _state = MutableStateFlow<Recording?>(null)
    val state: StateFlow<Recording?> = _state.asStateFlow()

    init {
        if (journal.exists()) restore()
    }

    fun start() {
        if (_state.value != null) return
        segments.clear()
        stats = TrackStats()
        startTime = System.currentTimeMillis()
        pausedMillis = 0
        pausedSince = null
        newSegment = true
        journal.writeText("")
        open()
        publish()
        ensureService()
    }

    fun pause() {
        if (_state.value == null || pausedSince != null) return
        val now = System.currentTimeMillis()
        pausedSince = now
        write { TrackJournal.appendPause(it, now) }
        publish()
    }

    fun resume() {
        val since = pausedSince ?: return
        val now = System.currentTimeMillis()
        pausedMillis += (now - since).coerceAtLeast(0)
        pausedSince = null
        newSegment = true // Don't draw a straight line across the pause.
        write { TrackJournal.appendResume(it, now) }
        publish()
    }

    /** Called by [TrackingService] with every GPS fix. */
    fun onLocation(location: Location) {
        if (writer == null || pausedSince != null) return
        if (location.hasAccuracy() && location.accuracy > MAX_ACCURACY_M) return
        val position = runCatching { LatLon(location.latitude, location.longitude, LocationRepository.altitude(location)) }
            .getOrNull() ?: return
        val point = TrackPoint(position, location.time)
        val startSegment = newSegment || segments.isEmpty()
        val last = segments.lastOrNull()?.lastOrNull()
        // Standing still, GPS wanders a few metres: don't record that as walking.
        if (!startSegment && last != null && Geo.distance(last.position, position) < MIN_STEP_M) return

        write {
            if (startSegment) TrackJournal.appendSegment(it)
            TrackJournal.append(it, point)
        }
        if (startSegment) segments += mutableListOf<TrackPoint>()
        segments.last() += point
        stats.add(point, startSegment)
        newSegment = false
        publish()
    }

    /** Ends the recording and saves it as [name]; returns the saved track, or null when empty. */
    suspend fun save(name: String?): SavedTrack? {
        if (_state.value == null) return null
        val kept = segments.map { it.toList() }.filter { it.isNotEmpty() }
        val saved = if (kept.isNotEmpty()) tracks.save(kept, name) else null
        end()
        return saved
    }

    /** Saves the way so far as [name] and keeps recording, e.g. to follow it back to the start. */
    suspend fun saveSoFar(name: String): SavedTrack? {
        if (_state.value == null) return null
        val kept = segments.map { it.toList() }.filter { it.isNotEmpty() }
        return if (kept.isNotEmpty()) tracks.save(kept, name) else null
    }

    fun discard() {
        if (_state.value != null) end()
    }

    private fun end() {
        runCatching { writer?.close() }
        writer = null
        journal.delete()
        segments.clear()
        _state.value = null
    }

    private fun restore() {
        runCatching {
            val contents = journal.reader().use { TrackJournal.read(it) }
            contents.segments.forEach { segments += it.toMutableList() }
            pausedMillis = contents.pausedMillis
            pausedSince = contents.pausedSince
        }.onFailure { Log.e(TAG, "Cannot read journal", it) }
        stats = TrackStats.of(segments)
        startTime = stats.startTime.takeIf { it > 0 } ?: journal.lastModified()
        newSegment = true
        open()
        publish()
        // Called when the app (re)starts, in the foreground: the service may start.
        ensureService()
    }

    private fun open() {
        writer = FileWriter(journal, true).buffered()
    }

    private fun write(block: (Writer) -> Unit) {
        val out = writer ?: return
        runCatching {
            block(out)
            out.flush()
        }.onFailure { Log.e(TAG, "Cannot write journal", it) }
    }

    /**
     * Starts the recording service if a recording is on. Only call right after user actions
     * or when the app comes to the foreground: Android forbids starting it from the background.
     */
    fun ensureService() {
        if (_state.value == null) return
        runCatching { TrackingService.start(context) }
            .onFailure { Log.w(TAG, "Cannot start recording service", it) }
    }

    private fun publish() {
        _state.value = Recording(
            startTime = startTime,
            distance = stats.distance,
            ascent = stats.ascent,
            descent = stats.descent,
            pausedMillis = pausedMillis,
            pausedSince = pausedSince,
            segments = segments.map { s -> s.map(TrackPoint::position) },
        )
    }

    private companion object {
        const val TAG = "RecordingRepository"

        /** Fixes worse than this (in tunnels, deep gorges, right after start) are dropped. */
        const val MAX_ACCURACY_M = 30f

        const val MIN_STEP_M = 5.0
    }
}
