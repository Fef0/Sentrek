package app.sentrek.record

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
    val totalsAllTime: StateFlow<Boolean> =
        settings.tripTotalsAllTime.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun setTotalsAllTime(allTime: Boolean) {
        viewModelScope.launch { settings.setTripTotalsAllTime(allTime) }
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

    fun setColor(track: SavedTrack, color: Int) {
        viewModelScope.launch { repository.setColor(track, color) }
    }
}
