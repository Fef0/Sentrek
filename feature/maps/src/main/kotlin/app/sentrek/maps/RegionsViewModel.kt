package app.sentrek.maps

import androidx.lifecycle.ViewModel
import app.sentrek.geo.RegionCell
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

@HiltViewModel
class RegionsViewModel @Inject constructor(private val repository: RegionRepository) : ViewModel() {
    val regions: StateFlow<Map<RegionCell, RegionState>> = repository.state

    private val _selection = MutableStateFlow<Set<RegionCell>>(emptySet())
    val selection: StateFlow<Set<RegionCell>> = _selection.asStateFlow()

    fun toggle(cell: RegionCell) {
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
}
