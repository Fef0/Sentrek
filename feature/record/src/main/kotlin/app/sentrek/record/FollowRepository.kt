package app.sentrek.record

import android.content.Context
import android.location.Location
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import app.sentrek.data.SettingsRepository
import app.sentrek.geo.LatLon
import app.sentrek.geo.route.OffRouteDetector
import app.sentrek.geo.route.Route
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** The route being followed, as the UI sees it. */
data class Following(
    val trackId: String,
    val name: String,
    val reversed: Boolean,
    /** Distance, climb and walking time to the end; the whole route until the first fix. */
    val remaining: Route.Remaining,
    /** Metres from the route, or null before the first fix. */
    val offset: Double?,
    val offRoute: Boolean,
    val hasElevation: Boolean,
    /** Distance from the start to height, in the walking direction; empty without heights. */
    val profile: List<Pair<Double, Double>> = emptyList(),
    /** Metres along the route to the point nearest the user, or null before the first fix. */
    val along: Double? = null,
) {
    /**
     * Where the user is along the route, only while actually on it: off it, the nearest
     * point of the route is not where they are.
     */
    val here: Double?
        get() = along?.takeIf { !offRoute && offset != null && offset <= OffRouteDetector.OFF_ROUTE_M }
}

/**
 * Follows a saved track: progress along it, what's left, and a vibration when the user
 * leaves the route or gets back on it. The followed track id is kept on disk, so following
 * survives an app restart.
 */
@Singleton
class FollowRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val tracks: TrackRepository,
    settings: SettingsRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val saved = File(context.filesDir, "following.txt")

    /** The off-route alert still shows on screen and in the notification when this is off. */
    private val vibrationOn = settings.offRouteVibration.stateIn(scope, SharingStarted.Eagerly, true)

    private var route: Route? = null
    private var along: Double? = null
    private var detector = OffRouteDetector()

    private val _state = MutableStateFlow<Following?>(null)
    val state: StateFlow<Following?> = _state.asStateFlow()

    init {
        // "<track id>\n<reversed>"
        val lines = runCatching { saved.readLines() }.getOrNull()
        val id = lines?.firstOrNull()
        if (id != null) {
            scope.launch {
                // The track list loads asynchronously.
                val track = tracks.tracks.first { it.isNotEmpty() }.firstOrNull { it.id == id }
                if (track != null) start(track, reversed = lines.getOrNull(1) == "true") else stop()
            }
        }
    }

    suspend fun start(track: SavedTrack, reversed: Boolean = false): Boolean {
        val loaded = tracks.route(track) ?: return false
        route = if (reversed) loaded.reversed() else loaded
        along = null
        detector = OffRouteDetector()
        tracks.show(track)
        runCatching { saved.writeText("${track.id}\n$reversed") }
        _state.value = Following(
            trackId = track.id,
            name = track.name,
            reversed = reversed,
            remaining = route!!.remaining(0.0),
            offset = null,
            offRoute = false,
            hasElevation = route!!.hasElevation,
            profile = route!!.profile(),
        )
        ensureService()
        return true
    }

    /** Starts the tracking service if following. Only from the foreground (see [RecordingRepository.ensureService]). */
    fun ensureService() {
        if (_state.value == null) return
        runCatching { TrackingService.start(context) }
            .onFailure { Log.w(TAG, "Cannot start tracking service", it) }
    }

    /** Stops following [trackId], e.g. because it's being deleted. */
    fun stopIfFollowing(trackId: String) {
        if (_state.value?.trackId == trackId) stop()
    }

    /** Walk the route the other way: the end becomes the start. */
    suspend fun reverse() {
        val current = _state.value ?: return
        val track = tracks.find(current.trackId) ?: return
        start(track, reversed = !current.reversed)
    }

    fun stop() {
        route = null
        along = null
        saved.delete()
        _state.value = null
    }

    /** Called by the tracking service with every GPS fix. */
    fun onLocation(location: Location) {
        val r = route ?: return
        val current = _state.value ?: return
        // Poor fixes would raise false off-route alarms.
        if (location.hasAccuracy() && location.accuracy > MAX_ACCURACY_M) return
        val here = runCatching { LatLon(location.latitude, location.longitude) }.getOrNull() ?: return
        val position = r.locate(here, along)
        along = position.along
        when (detector.update(position.offset, location.time)) {
            OffRouteDetector.Event.OffRoute -> vibrate(OFF_ROUTE_PATTERN)
            OffRouteDetector.Event.BackOnRoute -> vibrate(BACK_ON_ROUTE_PATTERN)
            null -> Unit
        }
        _state.value = current.copy(
            remaining = r.remaining(position.along),
            offset = position.offset,
            offRoute = detector.isOff,
            along = position.along,
        )
    }

    private fun vibrate(pattern: LongArray) {
        if (!vibrationOn.value) return
        runCatching {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService(VibratorManager::class.java).defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(pattern, -1)
            }
        }.onFailure { Log.w(TAG, "Cannot vibrate", it) }
    }

    private companion object {
        const val TAG = "FollowRepository"
        const val MAX_ACCURACY_M = 40f

        /** Three long pulses: hard to miss in a pocket. */
        val OFF_ROUTE_PATTERN = longArrayOf(0, 700, 300, 700, 300, 700)

        /** Two short pulses. */
        val BACK_ON_ROUTE_PATTERN = longArrayOf(0, 150, 120, 150)
    }
}
