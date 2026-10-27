package app.sentrek.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.sentrek.data.SettingsRepository
import app.sentrek.map.RasterOverlay
import app.sentrek.maps.ImportedMap
import app.sentrek.maps.ImportedMapsRepository
import app.sentrek.maps.RegionRepository
import app.sentrek.maps.TrailsDownload
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.io.File
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    regions: RegionRepository,
    importedMaps: ImportedMapsRepository,
    settings: SettingsRepository,
) : ViewModel() {
    /** Trail overlay files to draw, by cell id; empty when the user turned them off. */
    val trailFiles: StateFlow<Map<String, File>> =
        combine(regions.state, settings.showTrailDifficulty) { all, show ->
            if (!show) return@combine emptyMap()
            all.values.filter { it.trails == TrailsDownload.Ready }
                .associate { it.cell.id to regions.trailFile(it.cell) }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    val rasters: StateFlow<List<RasterOverlay>> = importedMaps.maps
        .map { maps -> maps.map(ImportedMap::overlay) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
}
