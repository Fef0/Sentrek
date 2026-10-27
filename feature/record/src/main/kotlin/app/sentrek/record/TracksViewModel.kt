package app.sentrek.record

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.sentrek.geo.BoundingBox
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

@HiltViewModel
class TracksViewModel @Inject constructor(
    private val repository: TrackRepository,
    private val follow: FollowRepository,
) : ViewModel() {
    val tracks: StateFlow<List<SavedTrack>> = repository.tracks

    val shown: StateFlow<Set<String>> = repository.shown
        .map { it.keys }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    /** Id of the track being followed. */
    val following: StateFlow<String?> = follow.state
        .map { it?.trackId }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun shareCopy(track: SavedTrack, onReady: (File) -> Unit) {
        viewModelScope.launch { repository.shareCopy(track)?.let(onReady) }
    }

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

    fun rename(track: SavedTrack, name: String) {
        viewModelScope.launch { repository.rename(track, name) }
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

    fun setColor(track: SavedTrack, color: Int) {
        viewModelScope.launch { repository.setColor(track, color) }
    }
}
