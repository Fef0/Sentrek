package app.sentrek.maps

import android.net.Uri
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.sentrek.feature.maps.R
import app.sentrek.geo.BoundingBox
import app.sentrek.geo.RegionCell
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class RegionsViewModel @Inject constructor(
    private val repository: RegionRepository,
    private val importedMaps: ImportedMapsRepository,
) : ViewModel() {
    val regions: StateFlow<Map<RegionCell, RegionState>> = repository.state
    val imported: StateFlow<List<ImportedMap>> = importedMaps.maps

    private val _selection = MutableStateFlow<Set<RegionCell>>(emptySet())
    val selection: StateFlow<Set<RegionCell>> = _selection.asStateFlow()

    private val _importing = MutableStateFlow(false)
    val importing: StateFlow<Boolean> = _importing.asStateFlow()

    /** Error to show in the panel, cleared by the next action. */
    private val _message = MutableStateFlow<Int?>(null)
    val message: StateFlow<Int?> = _message.asStateFlow()

    /** Area the map should move to, e.g. a freshly imported map. */
    private val _flyTo = MutableStateFlow<BoundingBox?>(null)
    val flyTo: StateFlow<BoundingBox?> = _flyTo.asStateFlow()

    fun toggle(cell: RegionCell) {
        _message.value = null
        _selection.value = _selection.value.let { if (cell in it) it - cell else it + cell }
    }

    fun clearSelection() {
        _selection.value = emptySet()
    }

    fun downloadSelected() {
        repository.download(_selection.value.filter { it !in regions.value })
        clearSelection()
    }

    fun deleteSelected() {
        repository.delete(_selection.value.filter { it in regions.value })
        clearSelection()
    }

    fun retryTrails() = repository.retryTrails(regions.value.keys)

    fun import(uri: Uri) {
        _message.value = null
        _importing.value = true
        viewModelScope.launch {
            when (val result = importedMaps.import(uri)) {
                is ImportResult.Imported -> _flyTo.value = result.map.info.bounds
                ImportResult.VectorNotSupported -> show(R.string.import_vector)
                ImportResult.UnknownFormat -> show(R.string.import_unknown)
                ImportResult.Failed -> show(R.string.import_failed)
            }
            _importing.value = false
        }
    }

    fun showImported(map: ImportedMap) {
        _flyTo.value = map.info.bounds
    }

    fun deleteImported(map: ImportedMap) {
        viewModelScope.launch { importedMaps.delete(map) }
    }

    fun flyToHandled() {
        _flyTo.value = null
    }

    private fun show(@StringRes message: Int) {
        _message.value = message
    }
}
