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
class TracksViewModel @Inject constructor(private val repository: TrackRepository) : ViewModel() {
    val tracks: StateFlow<List<SavedTrack>> = repository.tracks

    val shown: StateFlow<Set<String>> = repository.shown
        .map { it.keys }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    fun shareCopy(track: SavedTrack, onReady: (File) -> Unit) {
        viewModelScope.launch { repository.shareCopy(track)?.let(onReady) }
    }

    fun show(track: SavedTrack, onShown: (BoundingBox) -> Unit) {
        viewModelScope.launch { repository.show(track)?.let(onShown) }
    }

    fun hide(track: SavedTrack) = repository.hide(track)

    fun rename(track: SavedTrack, name: String) {
        viewModelScope.launch { repository.rename(track, name) }
    }

    fun delete(track: SavedTrack) {
        viewModelScope.launch { repository.delete(track) }
    }

    fun export(track: SavedTrack, target: Uri, onDone: (Boolean) -> Unit) {
        viewModelScope.launch { onDone(repository.export(track, target)) }
    }
}
