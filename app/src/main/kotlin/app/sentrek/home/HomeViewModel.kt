package app.sentrek.home

import app.sentrek.map.FollowMode
import app.sentrek.geo.route.Shade
import app.sentrek.record.ShadeWatch
import app.sentrek.geo.weather.WetAhead
import app.sentrek.record.WetWatch
import app.sentrek.record.BatteryWatch
import app.sentrek.record.SignalWatch
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import android.location.Location
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.sentrek.data.GpsTiming
import app.sentrek.data.PowerMode
import app.sentrek.data.SettingsRepository
import app.sentrek.geo.LatLon
import app.sentrek.geo.RegionCell
import app.sentrek.geo.overlay.MapKind
import app.sentrek.geo.dem.TerrainTiles
import app.sentrek.geo.units.Units
import app.sentrek.geo.Barometric
import app.sentrek.record.BarometerRepository
import app.sentrek.record.ElevationRepository
import app.sentrek.record.WeatherRepository
import app.sentrek.record.WeatherResult
import app.sentrek.ui.Format
import app.sentrek.map.RasterOverlay
import app.sentrek.maps.ImportedMap
import app.sentrek.maps.ImportedMapsRepository
import app.sentrek.maps.RegionRepository
import app.sentrek.maps.MapDownload
import app.sentrek.maps.TrailsDownload
import app.sentrek.maps.RegionState
import app.sentrek.maps.isOnWifi
import app.sentrek.maps.isOnline
import app.sentrek.maps.onlineFlow
import app.sentrek.geo.place.Place
import app.sentrek.record.FollowRepository
import app.sentrek.record.GpsStatus
import app.sentrek.record.Satellites
import app.sentrek.record.PlaceRepository
import app.sentrek.record.Following
import app.sentrek.record.LocationRepository
import app.sentrek.record.Recording
import app.sentrek.record.RecordingRepository
import app.sentrek.record.SavedTrack
import app.sentrek.record.TrackRepository
import app.sentrek.map.MapTrack
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

/** A reminder put off comes back after a month. */
private const val REMIND_AGAIN_MS = 30L * 24 * 60 * 60 * 1000

@HiltViewModel
class HomeViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val regions: RegionRepository,
    importedMaps: ImportedMapsRepository,
    private val settings: SettingsRepository,
    private val tracks: TrackRepository,
    private val location: LocationRepository,
    private val recorder: RecordingRepository,
    private val follower: FollowRepository,
    private val placeRepository: PlaceRepository,
    private val elevation: ElevationRepository,
    private val barometer: BarometerRepository,
    private val weather: WeatherRepository,
    private val signal: SignalWatch,
    private val batteryWatch: BatteryWatch,
    private val wetWatch: WetWatch,
    private val shadeWatch: ShadeWatch,
) : ViewModel() {
    val powerMode: StateFlow<PowerMode> =
        settings.powerMode.stateIn(viewModelScope, SharingStarted.Eagerly, PowerMode.Normal)

    /**
     * Downloaded areas worth updating, when the user wants reminders and hasn't put one off
     * in the last month: the map screen asks about them.
     */
    val oldMaps: StateFlow<List<RegionCell>> =
        combine(regions.state, settings.mapUpdateReminders, settings.mapReminderSnoozedAt, settings.autoUpdateMaps) { states, on, snoozedAt, auto ->
            val now = System.currentTimeMillis()
            // Who updates automatically isn't reminded.
            if (!on || auto || now - snoozedAt < REMIND_AGAIN_MS) emptyList() else states.values.filter { it.isOld(now) }.map(RegionState::cell)
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    init {
        // Automatic updates, as the areas get listed at startup: on Wi-Fi only, never during a
        // hike, and each area once.
        viewModelScope.launch {
            val started = HashSet<RegionCell>()
            combine(regions.state, settings.autoUpdateMaps) { states, auto ->
                val now = System.currentTimeMillis()
                if (auto) states.values.filter { it.isOld(now, RegionState.AUTO_UPDATE_AFTER_MS) }.map(RegionState::cell) else emptyList()
            }.collect { due ->
                val fresh = due - started
                if (fresh.isEmpty() || !isOnWifi(context) || recorder.state.value != null) return@collect
                started += fresh
                regions.update(fresh)
            }
        }
    }

    /** Areas whose updates keep failing: the user is told once, then the count starts over. */
    val failingMaps: StateFlow<List<RegionCell>> = regions.state
        .map { states -> states.values.filter(RegionState::updateFailing).map(RegionState::cell) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun failingMapsSeen() {
        regions.forgetFailedUpdates(failingMaps.value)
    }

    /** False when offline: nothing was started. */
    fun updateOldMaps(): Boolean {
        snoozeMapReminder()
        if (!isOnline(context)) return false
        regions.update(oldMaps.value)
        return true
    }

    fun snoozeMapReminder() {
        viewModelScope.launch { settings.snoozeMapReminder(System.currentTimeMillis()) }
    }

    /** How often the map asks for a fix while on screen, power mode included. */
    val screenGpsMs: StateFlow<Long> = combine(settings.gpsTiming, settings.powerMode) { timing, mode ->
        timing.forMode(mode).screenMs
    }.stateIn(viewModelScope, SharingStarted.Eagerly, GpsTiming.Default.screenMs)

    /** Terrain for the contour lines, or null with them switched off (or saving power). */
    val contourTerrain: StateFlow<TerrainTiles?> = combine(settings.showContours, settings.powerMode) { show, mode ->
        elevation.takeIf { show && mode == PowerMode.Normal }
    }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Goes up when terrain tiles missed offline can be had again: contours and slopes are drawn anew. */
    val terrainRetries: StateFlow<Int> get() = elevation.retries

    /** Terrain for the steep slope shading, or null with it switched off (or saving power). */
    val slopeTerrain: StateFlow<TerrainTiles?> = combine(settings.showSlopes, settings.powerMode) { show, mode ->
        elevation.takeIf { show && mode == PowerMode.Normal }
    }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val units: StateFlow<Units> = settings.units
        .stateIn(viewModelScope, SharingStarted.Eagerly, Format.units)

    /** Areas whose map is on the phone: offline, the rest of the world stays blank. */
    val downloadedCells: StateFlow<Set<RegionCell>> = regions.state
        .map { all -> all.values.filter { it.map is MapDownload.Ready }.map { it.cell }.toSet() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    val online: StateFlow<Boolean> = onlineFlow(context)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    /** Hiking overlay files to draw (trail difficulty + points), by cell id. */
    val overlays: StateFlow<Map<String, File>> = regions.state
        .map { all ->
            // While an area's trails update, the file it has stays on the map.
            all.values.filter { it.trails == TrailsDownload.Ready || regions.trailFile(it.cell).exists() }
                .associate { it.cell.id to regions.trailFile(it.cell) }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    /** Changes when an area's trails are replaced by newer ones: the map loads them again. */
    val overlaysVersion: StateFlow<Long> = regions.state
        .map { all -> all.values.filter { it.trails == TrailsDownload.Ready }.sumOf { regions.trailFile(it.cell).lastModified() } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0L)

    val showDifficulty: StateFlow<Boolean> =
        settings.showTrailDifficulty.stateIn(viewModelScope, SharingStarted.Eagerly, true)

    /** Which map is shown: hiking, cycling or winter. */
    val mapKind: StateFlow<MapKind> =
        settings.mapKind.stateIn(viewModelScope, SharingStarted.Eagerly, MapKind.Hiking)

    fun setMapKind(kind: MapKind) {
        viewModelScope.launch { settings.setMapKind(kind) }
    }

    /**
     * The shown map's own details (pistes, cycle routes) by cell id, and a number that changes
     * when one of them is replaced by a newer download, so the map loads it again.
     */
    val extraFiles: StateFlow<Pair<Map<String, File>, Long>> = combine(regions.state, mapKind) { all, kind ->
        if (kind == MapKind.Hiking) return@combine emptyMap<String, File>() to 0L
        // While an area's details update, the file it has stays on the map.
        val files = all.values.filter { kind in it.extras.keys && regions.extraFile(it.cell, kind).exists() }
            .associate { it.cell.id to regions.extraFile(it.cell, kind) }
        files to files.values.sumOf { it.lastModified() }
    }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap<String, File>() to 0L)

    /** The downloaded areas, for what the shown map is missing where the user looks. */
    val regionStates: StateFlow<Map<RegionCell, RegionState>> get() = regions.state

    /** Downloads the shown map's details for [cell], already on the phone. */
    fun downloadExtra(cell: RegionCell, kind: MapKind) {
        regions.download(listOf(cell), setOf(kind))
    }

    fun retryExtra(cell: RegionCell) {
        regions.retryTrails(listOf(cell))
    }

    // As in Settings › Map, for the map's layers sheet.
    val showContoursSetting: StateFlow<Boolean> =
        settings.showContours.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val showHillshadeSetting: StateFlow<Boolean> =
        settings.showHillshade.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val showSlopesSetting: StateFlow<Boolean> =
        settings.showSlopes.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun setShowContours(show: Boolean) {
        viewModelScope.launch { settings.setShowContours(show) }
    }

    fun setShowHillshade(show: Boolean) {
        viewModelScope.launch { settings.setShowHillshade(show) }
    }

    fun setShowSlopes(show: Boolean) {
        viewModelScope.launch { settings.setShowSlopes(show) }
    }

    fun setShowMapPoints(show: Boolean) {
        viewModelScope.launch { settings.setShowMapPoints(show) }
    }

    fun setShowDifficulty(show: Boolean) {
        viewModelScope.launch { settings.setShowTrailDifficulty(show) }
    }

    val rasters: StateFlow<List<RasterOverlay>> = importedMaps.maps
        .map { maps -> maps.map(ImportedMap::overlay) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val recording: StateFlow<Recording?> = recorder.state

    /** Saved tracks the user chose to show. */
    val shownTracks: StateFlow<List<MapTrack>> = combine(tracks.shown, tracks.tracks) { shown, all ->
        val byId = all.associateBy { it.id }
        shown.mapNotNull { (id, shape) ->
            byId[id]?.let { MapTrack(id, it.color, shape.segments, shape.waypoints) }
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val following: StateFlow<Following?> = follower.state

    /** The next long stretch in the sun on the followed route. */
    val sunny: StateFlow<Shade.Stretch?> = shadeWatch.state

    /** The first wet spot on the way, while recording or following. */
    val wet: StateFlow<WetAhead.Found?> = wetWatch.state

    /** How long the battery lasts at this rate, while recording or following. */
    val battery: StateFlow<BatteryWatch.State?> = batteryWatch.state

    fun setStaminaMode() {
        viewModelScope.launch { settings.setStaminaMode(true) }
    }

    /** The last place with a phone signal, while there is none here; null otherwise. */
    val lastSignal: StateFlow<SignalWatch.Spot?> = signal.state
        .map { s -> s?.last?.takeIf { !s.hasSignal } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Who gets the position by SMS while recording, and how often; null: nobody (see Breadcrumbs). */
    val breadcrumbs: StateFlow<Pair<String, Int>?> =
        combine(settings.sosEnabled, settings.breadcrumbMinutes, settings.emergencySmsTo, settings.emergencyContactName) { sos, minutes, to, name ->
            val allowed = ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED
            if (sos && minutes > 0 && to.isNotBlank() && allowed) name.ifBlank { to } to minutes else null
        }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val trailCardExpanded: StateFlow<Boolean> =
        settings.trailCardExpanded.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun setTrailCardExpanded(expanded: Boolean) {
        viewModelScope.launch { settings.setTrailCardExpanded(expanded) }
    }

    val followPlacesExpanded: StateFlow<Boolean> =
        settings.followPlacesExpanded.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun setFollowPlacesExpanded(expanded: Boolean) {
        viewModelScope.launch { settings.setFollowPlacesExpanded(expanded) }
    }

    val followNoteExpanded: StateFlow<Boolean> =
        settings.followNoteExpanded.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun setFollowNoteExpanded(expanded: Boolean) {
        viewModelScope.launch { settings.setFollowNoteExpanded(expanded) }
    }

    val autoHideControls: StateFlow<Boolean> =
        settings.autoHideControls.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val sosEnabled: StateFlow<Boolean> =
        settings.sosEnabled.stateIn(viewModelScope, SharingStarted.Eagerly, true)

    val showLockButton: StateFlow<Boolean> =
        settings.showLockButton.stateIn(viewModelScope, SharingStarted.Eagerly, true)

    /**
     * How the map follows the user, kept here so a map built again (battery saver coming on,
     * a theme change) goes on following the same way instead of stopping where it was.
     */
    var followMode: FollowMode = FollowMode.Off
    var resumeMode: FollowMode = FollowMode.North

    /** The relief shading: as chosen, never in stamina mode. */
    val showHillshade: StateFlow<Boolean> = combine(settings.showHillshade, settings.powerMode) { show, mode -> show && mode != PowerMode.Stamina }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val showTrackArrows: StateFlow<Boolean> =
        settings.showTrackArrows.stateIn(viewModelScope, SharingStarted.Eagerly, true)

    val showMapPoints: StateFlow<Boolean> =
        settings.showMapPoints.stateIn(viewModelScope, SharingStarted.Eagerly, true)

    /** The + and - buttons: as chosen, and always in glove mode. */
    val showZoomButtons: StateFlow<Boolean> = combine(settings.gloveMode, settings.zoomButtons) { gloves, show -> gloves || show }
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    val showScaleBar: StateFlow<Boolean> =
        settings.showScaleBar.stateIn(viewModelScope, SharingStarted.Eagerly, true)

    val rotationLocked: StateFlow<Boolean> =
        settings.rotationLocked.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun setRotationLocked(locked: Boolean) {
        viewModelScope.launch { settings.setRotationLocked(locked) }
    }

    val places: StateFlow<List<Place>> = placeRepository.places

    /** The place the arrow points to, if any. Kept while the app runs. */
    private val _goTo = MutableStateFlow<GoTo?>(null)
    val goTo: StateFlow<GoTo?> = _goTo.asStateFlow()

    fun goTo(target: GoTo?) {
        _goTo.value = target
    }

    fun place(id: String): Place? = placeRepository.find(id)

    fun addPlace(place: Place) {
        placeRepository.add(place.name, place.kind, place.position, place.note, place.color, place.photos)
    }

    fun updatePlace(place: Place) = placeRepository.update(place)

    fun deletePlace(place: Place) = placeRepository.delete(place)

    fun track(id: String): SavedTrack? = tracks.find(id)

    /** All saved tracks, as they change: a sheet or card open on one shows its edits. */
    val allTracks: StateFlow<List<SavedTrack>> get() = tracks.tracks

    fun editTrack(track: SavedTrack, name: String, note: String, photos: List<String>) {
        viewModelScope.launch { tracks.edit(track, name, note, photos) }
    }

    suspend fun trackProfile(track: SavedTrack) = tracks.profile(track)

    suspend fun weatherAt(at: LatLon): WeatherResult = weather.forecast(at)

    suspend fun trackWeather(track: SavedTrack): WeatherResult =
        tracks.highestPoint(track)?.let { weather.forecast(it) } ?: WeatherResult.Unavailable

    fun hideTrack(id: String) = tracks.hide(id)

    fun setTrackColor(track: SavedTrack, color: Int) {
        viewModelScope.launch { tracks.setColor(track, color) }
    }

    fun followTrack(track: SavedTrack) {
        viewModelScope.launch { follower.start(track) }
    }

    init {
        // The app is in the foreground: tracking interrupted by the system resumes now.
        recorder.ensureService()
        follower.ensureService()
    }

    fun stopFollowing() = follower.stop()

    fun reverseRoute() {
        viewModelScope.launch { follower.reverse() }
    }

    fun hasLocationPermission() = location.hasPermission()

    // The latest fix the map received, for the GPS status and the coordinates label.
    private val _lastFix = MutableStateFlow<Location?>(null)
    val lastFix: StateFlow<Location?> = _lastFix.asStateFlow()

    fun locations(intervalMs: Long): Flow<Location> = location.locations(intervalMs).onEach { _lastFix.value = it }

    /**
     * The GPS status while collected (the map on screen). Re-checked every couple of seconds,
     * since a fix going stale or location being switched off sends no event of its own.
     * Call again once permission is granted: without it there are no satellites to count.
     */
    fun gps(): Flow<GpsStatus> = combine(
        _lastFix,
        location.satellites().onStart { emit(Satellites(0, 0)) },
        flow { while (true) { emit(Unit); delay(GPS_CHECK_MS) } },
    ) { fix, satellites, _ -> GpsStatus.of(location.isGpsOn(), fix, satellites) }
        .distinctUntilChanged()

    /** Pressure, and the height from it once set against the map's terrain. */
    data class Barometer(val hPa: Double, val altitude: Double?)

    /** Kept while the app runs: set once against the terrain, then it follows the pressure. */
    private var barometerOffset: Double? = null

    /**
     * Only while collected (the position details open), and only in normal power mode: battery
     * saver and stamina mode keep the sensor off. Null then, and on phones without the sensor.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun barometer(): Flow<Barometer?> = settings.powerMode.flatMapLatest { mode ->
        if (mode == PowerMode.Normal) pressureReadings() else flowOf(null)
    }

    private fun pressureReadings(): Flow<Barometer> = barometer.pressures().onEach { hPa ->
        if (barometerOffset == null) {
            val fix = _lastFix.value ?: return@onEach
            val ground = elevation.withElevation(listOf(LatLon(fix.latitude, fix.longitude))).firstOrNull()?.ele
                ?: LocationRepository.altitude(fix)
                ?: return@onEach
            barometerOffset = Barometric.offset(hPa, ground)
        }
    }.map { hPa -> Barometer(hPa, barometerOffset?.let { Barometric.altitude(hPa) + it }) }

    fun startRecording() = recorder.start()

    fun pauseRecording() = recorder.pause()

    fun resumeRecording() = recorder.resume()

    fun saveRecording(name: String, note: String, photos: List<String>, onSaved: (SavedTrack?) -> Unit) {
        viewModelScope.launch { onSaved(recorder.save(name, note, photos)) }
    }

    fun discardRecording() = recorder.discard()

    /**
     * Follows the way walked so far back to where the recording started (as TracBack does on
     * GPS units). The recording goes on, the way back included. [onDone] gets false when
     * there was nothing to go back along.
     */
    fun backToStart(name: String, onDone: (Boolean) -> Unit) {
        viewModelScope.launch {
            val track = recorder.saveSoFar(name)
            onDone(track != null && follower.start(track, reversed = true))
        }
    }

    fun defaultTrackName(recording: Recording) = tracks.defaultName(recording.startTime)

    private companion object {
        const val GPS_CHECK_MS = 2_000L
    }
}
