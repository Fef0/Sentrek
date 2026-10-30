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
import app.sentrek.geo.overlay.OverlayPoiSource
import app.sentrek.geo.route.Route
import app.sentrek.geo.route.PaceTracker
import app.sentrek.geo.route.RoutePlace
import app.sentrek.geo.route.RoutePlaces
import app.sentrek.geo.route.UpcomingPlace
import app.sentrek.geo.route.WaterAhead
import app.sentrek.feature.record.R
import app.sentrek.ui.Format
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** The route being followed, as the UI sees it. */
data class Following(
    val trackId: String,
    val name: String,
    val reversed: Boolean,
    /**
     * Distance, climb and walking time to the end; the whole route until the first fix. The
     * time is at the user's own pace, once there's enough walking to tell it ([ownPace]).
     */
    val remaining: Route.Remaining,
    /** Metres from the route, or null before the first fix. */
    val offset: Double?,
    val offRoute: Boolean,
    val hasElevation: Boolean,
    /** Distance from the start to height, in the walking direction; empty without heights. */
    val profile: List<Pair<Double, Double>> = emptyList(),
    /** Metres along the route to the point nearest the user, or null before the first fix. */
    val along: Double? = null,
    /** Huts, water, passes, the user's places… still ahead, nearest first. */
    val upcoming: List<UpcomingPlace> = emptyList(),
    /** The times are more the user's pace than the standard one. */
    val ownPace: Boolean = false,
    /** The next water and the dry stretch after it, times at the user's pace; null: no water known on the route. */
    val water: WaterAhead? = null,
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
    private val places: PlaceRepository,
    private val pois: OverlayPoiSource,
    settings: SettingsRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val saved = File(context.filesDir, "following.txt")

    /** The off-route alert still shows on screen and in the notification when this is off. */
    private val vibrationOn = settings.offRouteVibration.stateIn(scope, SharingStarted.Eagerly, true)

    /** Off: no word about water, on screen or in the pocket. */
    private val waterOn = settings.waterAdvice.stateIn(scope, SharingStarted.Eagerly, true)

    private var route: Route? = null
    private var along: Double? = null
    private var routePlaces: List<RoutePlace> = emptyList()
    private var placesJob: Job? = null
    private var detector = OffRouteDetector()

    /** The user's pace on this route, learnt as it's walked: the times left follow it. */
    private var pace = PaceTracker()

    /** Where along the route the user was told to fill up: once per spring. */
    private var filledUpAt: Double? = null

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
        routePlaces = emptyList()
        detector = OffRouteDetector()
        pace = PaceTracker()
        filledUpAt = null
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
        findPlaces(track, route!!)
        ensureService()
        return true
    }

    /**
     * Looks up the places along [r] once, in the background: the route's waypoints, then the
     * user's places, then the hiking points of the downloaded areas (listed in that order, the
     * first wins where two name the same spot).
     */
    private fun findPlaces(track: SavedTrack, r: Route) {
        placesJob?.cancel()
        placesJob = scope.launch {
            val box = RoutePlaces.box(r) ?: return@launch
            val candidates = buildList {
                tracks.waypoints(track).forEach { add(RoutePlaces.Candidate(it.name, RoutePlace.Kind.Waypoint, it.position)) }
                places.places.value.forEach {
                    add(RoutePlaces.Candidate(it.name, RoutePlace.Kind.Saved(it.kind, it.displayColor), it.position))
                }
                pois.pois(box).filter { it.kind.landmark }
                    .forEach { add(RoutePlaces.Candidate(it.name, RoutePlace.Kind.Osm(it.kind), it.position)) }
            }
            val found = withContext(Dispatchers.Default) { RoutePlaces.along(r, candidates) }
            if (route !== r) return@launch
            routePlaces = found
            _state.value = _state.value?.copy(upcoming = upcoming(r, found, along ?: 0.0), water = water(r, found, along ?: 0.0))
        }
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
        placesJob?.cancel()
        route = null
        along = null
        routePlaces = emptyList()
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
        val standard = r.remaining(position.along)
        val onRoute = !detector.isOff && position.offset <= OffRouteDetector.OFF_ROUTE_M
        pace.update(position.along, standard.millis, location.time, onRoute)
        _state.value = current.copy(
            remaining = standard.copy(millis = atPace(standard.millis)),
            ownPace = pace.learnt,
            offset = position.offset,
            offRoute = detector.isOff,
            along = position.along,
            upcoming = upcoming(r, routePlaces, position.along),
            water = water(r, routePlaces, position.along)?.also(::fillUpAlert),
        )
    }

    private fun water(r: Route, found: List<RoutePlace>, at: Double): WaterAhead? =
        if (!waterOn.value) null else WaterAhead.of(r, found, at)?.let { w -> w.copy(next = w.next?.let { it.copy(millis = atPace(it.millis)) }, dryMillis = atPace(w.dryMillis)) }

    /** Close to the last water before a long dry stretch: a word, once, before walking past it. */
    private fun fillUpAlert(water: WaterAhead) {
        val next = water.next ?: return
        if (!water.long || next.distance > FILL_UP_M || filledUpAt == next.place.along) return
        filledUpAt = next.place.along
        val text = context.getString(
            R.string.water_alert_text,
            Format.distance(water.dryDistance),
            Format.hoursMinutes(water.dryMillis),
        )
        TrailAlerts.ring(context, WATER_NOTIFICATION_ID, context.getString(R.string.water_alert_title), text, WATER_PATTERN)
    }

    /**
     * Points of the way ahead, every [stepM] metres from where the user is and the end: metres
     * along the route and where. Empty when not following.
     */
    fun wayAhead(stepM: Double, max: Int): List<Pair<Double, LatLon>> {
        val r = route ?: return emptyList()
        val from = along ?: 0.0
        val alongs = generateSequence(from) { it + stepM }.takeWhile { it < r.length }.take(max - 1).toList() + r.length
        return alongs.map { it to r.pointAt(it) }
    }

    /** Walking time from where the user is to [to] metres along the route, at their pace. */
    fun timeTo(to: Double): Long? {
        val r = route ?: return null
        val here = r.remaining(along ?: 0.0).millis
        return atPace(maxOf(0L, here - r.remaining(to).millis))
    }

    /** Metres along the route to the user, null before the first fix or not following. */
    val alongNow: Double? get() = along.takeIf { route != null }

    private fun atPace(standardMillis: Long): Long = (standardMillis * pace.factor).toLong()

    private fun upcoming(r: Route, found: List<RoutePlace>, at: Double): List<UpcomingPlace> =
        RoutePlaces.upcoming(r, found, at).map { it.copy(millis = atPace(it.millis)) }

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
        const val FILL_UP_M = 150.0
        const val WATER_NOTIFICATION_ID = 7

        /** Three short pulses, as a drip. */
        val WATER_PATTERN = longArrayOf(0, 200, 150, 200, 150, 200)

        /** Three long pulses: hard to miss in a pocket. */
        val OFF_ROUTE_PATTERN = longArrayOf(0, 700, 300, 700, 300, 700)

        /** Two short pulses. */
        val BACK_ON_ROUTE_PATTERN = longArrayOf(0, 150, 120, 150)
    }
}
