package app.sentrek.home

import android.location.Location
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.sentrek.data.SettingsRepository
import app.sentrek.geo.LatLon
import app.sentrek.map.RasterOverlay
import app.sentrek.maps.ImportedMap
import app.sentrek.maps.ImportedMapsRepository
import app.sentrek.maps.RegionRepository
import app.sentrek.maps.TrailsDownload
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    regions: RegionRepository,
    importedMaps: ImportedMapsRepository,
    private val settings: SettingsRepository,
    private val tracks: TrackRepository,
    private val location: LocationRepository,
    private val recorder: RecordingRepository,
    private val follower: FollowRepository,
    private val placeRepository: PlaceRepository,
) : ViewModel() {
    /** Hiking overlay files to draw (trail difficulty + points), by cell id. */
    val overlays: StateFlow<Map<String, File>> = regions.state
        .map { all ->
            all.values.filter { it.trails == TrailsDownload.Ready }
                .associate { it.cell.id to regions.trailFile(it.cell) }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    val showDifficulty: StateFlow<Boolean> =
        settings.showTrailDifficulty.stateIn(viewModelScope, SharingStarted.Eagerly, true)

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

    val followProfile: StateFlow<Boolean> =
        settings.followProfile.stateIn(viewModelScope, SharingStarted.Eagerly, true)

    val autoHideControls: StateFlow<Boolean> =
        settings.autoHideControls.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun setFollowProfile(show: Boolean) {
        viewModelScope.launch { settings.setFollowProfile(show) }
    }

    val places: StateFlow<List<Place>> = placeRepository.places

    fun place(id: String): Place? = placeRepository.find(id)

    fun addPlace(place: Place) {
        placeRepository.add(place.name, place.kind, place.position, place.note, place.color)
    }

    fun updatePlace(place: Place) = placeRepository.update(place)

    fun deletePlace(place: Place) = placeRepository.delete(place)

    fun track(id: String): SavedTrack? = tracks.find(id)

    suspend fun trackProfile(track: SavedTrack) = tracks.profile(track)

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

    fun locations(): Flow<Location> = location.locations().onEach { _lastFix.value = it }

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

    fun startRecording() = recorder.start()

    fun pauseRecording() = recorder.pause()

    fun resumeRecording() = recorder.resume()

    fun saveRecording(name: String, onSaved: (SavedTrack?) -> Unit) {
        viewModelScope.launch { onSaved(recorder.save(name)) }
    }

    fun discardRecording() = recorder.discard()

    fun defaultTrackName(recording: Recording) = tracks.defaultName(recording.startTime)

    private companion object {
        const val GPS_CHECK_MS = 2_000L
    }
}
