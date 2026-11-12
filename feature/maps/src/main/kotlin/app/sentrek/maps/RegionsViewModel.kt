package app.sentrek.maps

import android.content.Context
import android.net.Uri
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.sentrek.data.SettingsRepository
import app.sentrek.feature.maps.R
import app.sentrek.geo.BoundingBox
import app.sentrek.geo.RegionCell
import app.sentrek.geo.overlay.MapKind
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class RegionsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: RegionRepository,
    private val importedMaps: ImportedMapsRepository,
    settings: SettingsRepository,
) : ViewModel() {
    private val useMobileData = settings.useMobileData.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** True when a download started now would go over mobile data the user hasn't allowed: asked first. */
    fun onMobileData(): Boolean = isOnline(context) && !isUnmetered(context) && !useMobileData.value

    val regions: StateFlow<Map<RegionCell, RegionState>> = repository.state
    val status: StateFlow<DownloadStatus?> = repository.status
    val imported: StateFlow<List<ImportedMap>> = importedMaps.maps

    private val _selection = MutableStateFlow<Set<RegionCell>>(emptySet())
    val selection: StateFlow<Set<RegionCell>> = _selection.asStateFlow()

    /**
     * The extra maps for the selected areas: those of the first area picked, already on the
     * phone, or the last ones downloaded for a new one.
     */
    private val _extras = MutableStateFlow(repository.lastExtras)
    val extras: StateFlow<Set<MapKind>> = _extras.asStateFlow()

    fun toggleExtra(kind: MapKind) {
        _message.value = null
        _extras.value = _extras.value.let { if (kind in it) it - kind else it + kind }
    }

    private val _importing = MutableStateFlow(false)
    val importing: StateFlow<Boolean> = _importing.asStateFlow()

    /** Error to show in the panel, cleared by the next action. */
    private val _message = MutableStateFlow<Int?>(null)
    val message: StateFlow<Int?> = _message.asStateFlow()

    /** Area the map should move to, e.g. a freshly imported map. */
    private val _flyTo = MutableStateFlow<BoundingBox?>(null)
    val flyTo: StateFlow<BoundingBox?> = _flyTo.asStateFlow()

    /** About how many bytes downloading [cells] takes, and the space left on the phone. */
    fun estimate(cells: Collection<RegionCell>): Pair<Long, Long> = repository.estimatedBytes(cells) to repository.freeBytes()

    fun toggle(cell: RegionCell) {
        _message.value = null
        if (_selection.value.isEmpty()) _extras.value = regions.value[cell]?.extras?.keys ?: repository.lastExtras
        _selection.value = _selection.value.let { if (cell in it) it - cell else it + cell }
    }

    fun clearSelection() {
        _selection.value = emptySet()
    }

    fun downloadSelected() {
        // Nothing would come: say why, and keep the selection for when the network is back.
        if (!isOnline(context)) return show(R.string.regions_offline)
        _message.value = null
        // Areas already there get the extra maps too, none taken away.
        repository.download(_selection.value, _extras.value)
        repository.lastExtras = _extras.value
        clearSelection()
    }

    /** The selected areas on the phone get the extra maps chosen, and lose the others. */
    fun saveExtras() {
        val cells = _selection.value.filter { it in regions.value }
        if (extrasToDownload() && !isOnline(context)) return show(R.string.regions_offline)
        _message.value = null
        repository.setExtras(cells, _extras.value)
        clearSelection()
    }

    /** Whether [saveExtras] would download something, not only take maps away. */
    fun extrasToDownload(): Boolean = _selection.value.filter { it in regions.value }
        .any { (_extras.value - regions.value[it]?.extras?.keys.orEmpty()).isNotEmpty() }

    /** Stops every download; see RegionRepository.stop. */
    fun stopDownloads() {
        _message.value = null
        repository.stop()
    }

    fun deleteSelected() {
        repository.delete(_selection.value.filter { it in regions.value })
        clearSelection()
    }

    /** The selected areas already on the phone, downloaded again. */
    fun updateSelected() {
        if (!isOnline(context)) return show(R.string.regions_offline)
        _message.value = null
        repository.update(_selection.value.filter { regions.value[it]?.map is MapDownload.Ready }) { show(R.string.regions_up_to_date) }
        clearSelection()
    }

    fun updateOld() {
        if (!isOnline(context)) return show(R.string.regions_offline)
        _message.value = null
        val now = System.currentTimeMillis()
        repository.update(regions.value.values.filter { it.isOld(now) }.map(RegionState::cell)) { show(R.string.regions_up_to_date) }
    }

    fun retryTrails() {
        if (!isOnline(context)) return show(R.string.regions_offline)
        _message.value = null
        repository.retryTrails(regions.value.keys)
    }

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
