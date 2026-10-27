package app.sentrek.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.sentrek.maps.RegionRepository
import app.sentrek.maps.TrailsDownload
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.io.File
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(repository: RegionRepository) : ViewModel() {
    /** Trail overlay files to draw, by cell id. */
    val trailFiles: StateFlow<Map<String, File>> = repository.state
        .map { all ->
            all.values.filter { it.trails == TrailsDownload.Ready }
                .associate { it.cell.id to repository.trailFile(it.cell) }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())
}
