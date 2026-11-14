package app.sentrek.record

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.sentrek.data.STATS_THIS_YEAR
import app.sentrek.data.SettingsRepository
import app.sentrek.geo.BoundingBox
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class TracksViewModel @Inject constructor(
    private val repository: TrackRepository,
    private val follow: FollowRepository,
    private val settings: SettingsRepository,
) : ViewModel() {
    val tracks: StateFlow<List<SavedTrack>> = repository.tracks

    /** Whether the walking totals cover all time rather than this year; remembered. */
    /** The year the stats show: STATS_THIS_YEAR, STATS_ALL_TIME, or a year. */
    val statsYear: StateFlow<Int> =
        settings.walkStatsYear.stateIn(viewModelScope, SharingStarted.Eagerly, STATS_THIS_YEAR)

    val statsOpen: StateFlow<Boolean> =
        settings.walkStatsOpen.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun setStatsOpen(open: Boolean) {
        viewModelScope.launch { settings.setWalkStatsOpen(open) }
    }

    fun setStatsYear(year: Int) {
        viewModelScope.launch { settings.setWalkStatsYear(year) }
    }

    /** Notes and photos open, or in one line; remembered, for all the tracks at once. */
    val notesExpanded: StateFlow<Boolean> =
        settings.trackNotesExpanded.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun setNotesExpanded(expanded: Boolean) {
        viewModelScope.launch { settings.setTrackNotesExpanded(expanded) }
    }

    val shown: StateFlow<Set<String>> = repository.shown
        .map { it.keys }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    /** Id of the track being followed. */
    val following: StateFlow<String?> = follow.state
        .map { it?.trackId }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun show(track: SavedTrack, onShown: (BoundingBox) -> Unit) {
        viewModelScope.launch { repository.show(track)?.let(onShown) }
    }

    fun hide(track: SavedTrack) = repository.hide(track)

    suspend fun profile(track: SavedTrack) = repository.profile(track)

    suspend fun sections(track: SavedTrack) = repository.sections(track)

    /** Starts following [track]; [onStarted] gets the route's bounds to show it. */
    fun follow(track: SavedTrack, onStarted: (BoundingBox?) -> Unit) {
        viewModelScope.launch {
            if (follow.start(track)) onStarted(repository.show(track))
        }
    }

    fun stopFollowing() = follow.stop()

    fun edit(track: SavedTrack, name: String, note: String, photos: List<String>) {
        viewModelScope.launch { repository.edit(track, name, note, photos) }
    }

    fun delete(track: SavedTrack) {
        follow.stopIfFollowing(track.id)
        viewModelScope.launch { repository.delete(track) }
    }

    fun export(track: SavedTrack, target: Uri, kml: Boolean, onDone: (Boolean) -> Unit) {
        viewModelScope.launch { onDone(repository.export(track, target, kml)) }
    }

    fun import(uris: List<Uri>, onDone: (ImportResult) -> Unit) {
        viewModelScope.launch { onDone(repository.importAll(uris)) }
    }

    /** Saves a copy of [track] as [name]; [onDone] gets the copy, or null. */
    fun saveCopy(track: SavedTrack, name: String, onDone: (SavedTrack?) -> Unit) {
        viewModelScope.launch { onDone(repository.saveCopy(track, name)) }
    }

    /** Saves [track] the other way round as [name]; [onDone] gets the copy, or null. */
    fun saveReversed(track: SavedTrack, name: String, onDone: (SavedTrack?) -> Unit) {
        viewModelScope.launch { onDone(repository.saveReversed(track, name)) }
    }

    /** Counts [track] as one of the user's walks, or not. */
    fun setWalk(track: SavedTrack, walk: Boolean) {
        viewModelScope.launch { repository.setWalk(track, walk) }
    }

    /** All of [tracks] that can be walks, counted as walks. */
    fun setWalks(tracks: List<SavedTrack>) {
        viewModelScope.launch { tracks.forEach { repository.setWalk(it, true) } }
    }

    /** Copies the walk [track] to the routes as [name]; [onDone] gets the copy, or null. */
    fun copyAsRoute(track: SavedTrack, name: String, onDone: (SavedTrack?) -> Unit) {
        viewModelScope.launch { onDone(repository.copyAsRoute(track, name)) }
    }

    fun setColor(track: SavedTrack, color: Int) {
        viewModelScope.launch { repository.setColor(track, color) }
    }
}
