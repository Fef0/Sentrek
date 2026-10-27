package app.sentrek.maps

import android.content.Context
import android.util.Log
import app.sentrek.geo.RegionCell
import app.sentrek.geo.RegionGrid
import app.sentrek.map.MapStyle
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.offline.OfflineManager
import org.maplibre.android.offline.OfflineRegion
import org.maplibre.android.offline.OfflineRegionError
import org.maplibre.android.offline.OfflineRegionStatus
import org.maplibre.android.offline.OfflineTilePyramidRegionDefinition
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

sealed interface MapDownload {
    data object Queued : MapDownload
    data class Downloading(val fraction: Float) : MapDownload
    data class Ready(val bytes: Long) : MapDownload
}

enum class TrailsDownload { Missing, Downloading, Ready, Failed }

data class RegionState(val cell: RegionCell, val map: MapDownload, val trails: TrailsDownload)

/**
 * Downloaded map areas. Each grid cell is one MapLibre offline region (vector tiles z0–14,
 * terrain z0–12, fonts) plus one trail-difficulty overlay file.
 *
 * Map regions download one at a time: OpenFreeMap's terms cap each download at 3,000 tiles
 * and 4 parallel requests. Trail overlays are also fetched one at a time (Overpass policy).
 * All MapLibre calls happen on the main thread, as the SDK requires.
 */
@Singleton
class RegionRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val trailSource: TrailOverlaySource,
) {
    private val offline = OfflineManager.getInstance(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val trailsDir = File(context.filesDir, "trails").apply {
        mkdirs()
        // Older formats are refetched: restore() treats a missing file as "to download".
        listFiles { f -> !f.name.endsWith(OVERLAY_SUFFIX) }?.forEach { it.delete() }
    }
    private val trailsLock = Mutex()

    private val regions = HashMap<RegionCell, OfflineRegion>()
    private val queue = ArrayDeque<RegionCell>()
    private var active: RegionCell? = null

    private val _state = MutableStateFlow<Map<RegionCell, RegionState>>(emptyMap())
    val state: StateFlow<Map<RegionCell, RegionState>> = _state.asStateFlow()

    init {
        offline.listOfflineRegions(object : OfflineManager.ListOfflineRegionsCallback {
            override fun onList(offlineRegions: Array<OfflineRegion>?) = restore(offlineRegions.orEmpty())
            override fun onError(error: String) {
                Log.e(TAG, "Cannot list offline regions: $error")
            }
        })
    }

    fun trailFile(cell: RegionCell): File = File(trailsDir, cell.id + OVERLAY_SUFFIX)

    fun download(cells: Collection<RegionCell>) {
        for (cell in cells) {
            if (cell in regions || cell in _state.value) continue
            put(RegionState(cell, MapDownload.Queued, trailsStateOnDisk(cell)))
            val definition = OfflineTilePyramidRegionDefinition(
                MapStyle.Hiking.offlineUrl,
                cell.bbox.let { LatLngBounds.from(it.north, it.east, it.south, it.west) },
                0.0,
                RegionGrid.MAX_DOWNLOAD_ZOOM.toDouble(),
                context.resources.displayMetrics.density,
            )
            offline.createOfflineRegion(definition, cell.id.toByteArray(), object : OfflineManager.CreateOfflineRegionCallback {
                override fun onCreate(offlineRegion: OfflineRegion) {
                    regions[cell] = offlineRegion
                    queue.addLast(cell)
                    pump()
                }

                override fun onError(error: String) {
                    Log.e(TAG, "Cannot create region ${cell.id}: $error")
                    remove(cell)
                }
            })
            if (!trailFile(cell).exists()) fetchTrails(cell)
        }
        ensureService()
    }

    fun delete(cells: Collection<RegionCell>) {
        for (cell in cells) {
            queue.remove(cell)
            trailFile(cell).delete()
            val region = regions.remove(cell)
            if (active == cell) {
                region?.setDownloadState(OfflineRegion.STATE_INACTIVE)
                active = null
            }
            remove(cell)
            region?.delete(object : OfflineRegion.OfflineRegionDeleteCallback {
                override fun onDelete() = Unit
                override fun onError(error: String) {
                    Log.e(TAG, "Cannot delete region ${cell.id}: $error")
                }
            })
        }
        pump()
    }

    fun retryTrails(cells: Collection<RegionCell>) {
        cells.filter { _state.value[it]?.trails == TrailsDownload.Failed }.forEach(::fetchTrails)
    }

    private fun restore(saved: Array<out OfflineRegion>) {
        for (region in saved) {
            val cell = RegionCell.parse(String(region.metadata))
            if (cell == null) {
                region.delete(object : OfflineRegion.OfflineRegionDeleteCallback {
                    override fun onDelete() = Unit
                    override fun onError(error: String) = Unit
                })
                continue
            }
            regions[cell] = region
            put(RegionState(cell, MapDownload.Queued, trailsStateOnDisk(cell)))
            if (!trailFile(cell).exists()) {
                fetchTrails(cell)
                ensureService()
            }
            region.getStatus(object : OfflineRegion.OfflineRegionStatusCallback {
                override fun onStatus(status: OfflineRegionStatus?) {
                    if (status?.isComplete == true) {
                        updateMap(cell, MapDownload.Ready(status.completedResourceSize))
                    } else {
                        // Interrupted last time (app killed): resume.
                        queue.addLast(cell)
                        pump()
                        ensureService()
                    }
                }

                override fun onError(error: String?) {
                    Log.e(TAG, "Status of ${cell.id}: $error")
                }
            })
        }
    }

    /** Starts the next queued region when none is downloading. */
    private fun pump() {
        if (active != null) return
        val cell = queue.removeFirstOrNull() ?: return
        val region = regions[cell] ?: return pump()
        active = cell
        region.setObserver(object : OfflineRegion.OfflineRegionObserver {
            override fun onStatusChanged(status: OfflineRegionStatus) {
                if (active != cell) return
                if (status.isComplete) {
                    region.setDownloadState(OfflineRegion.STATE_INACTIVE)
                    region.setObserver(null)
                    updateMap(cell, MapDownload.Ready(status.completedResourceSize))
                    active = null
                    pump()
                } else {
                    val required = status.requiredResourceCount.coerceAtLeast(1)
                    updateMap(cell, MapDownload.Downloading(status.completedResourceCount.toFloat() / required))
                }
            }

            // MapLibre retries failed requests by itself, so these are informational.
            override fun onError(error: OfflineRegionError) {
                Log.w(TAG, "Region ${cell.id}: ${error.reason} ${error.message}")
            }

            override fun mapboxTileCountLimitExceeded(limit: Long) = Unit
        })
        updateMap(cell, MapDownload.Downloading(0f))
        region.setDownloadState(OfflineRegion.STATE_ACTIVE)
    }

    private fun fetchTrails(cell: RegionCell) {
        updateTrails(cell, TrailsDownload.Downloading)
        scope.launch {
            trailsLock.withLock {
                if (cell !in _state.value) return@withLock
                val result = runCatching { trailSource.fetch(cell.bbox, trailFile(cell)) }
                result.exceptionOrNull()?.let { Log.w(TAG, "Trails for ${cell.id} failed", it) }
                if (cell !in _state.value) {
                    trailFile(cell).delete() // Area deleted while downloading.
                } else {
                    updateTrails(cell, if (result.isSuccess) TrailsDownload.Ready else TrailsDownload.Failed)
                }
            }
        }
    }

    /**
     * Called only right after user actions or app launch: Android forbids starting a
     * foreground service from the background. The service stops itself when idle.
     */
    private fun ensureService() {
        runCatching { DownloadService.start(context) }
            .onFailure { Log.w(TAG, "Cannot start download service", it) }
    }

    private fun trailsStateOnDisk(cell: RegionCell) =
        if (trailFile(cell).exists()) TrailsDownload.Ready else TrailsDownload.Missing

    private fun put(state: RegionState) = _state.update { it + (state.cell to state) }

    private fun remove(cell: RegionCell) = _state.update { it - cell }

    private fun updateMap(cell: RegionCell, map: MapDownload) =
        _state.update { all -> all[cell]?.let { all + (cell to it.copy(map = map)) } ?: all }

    private fun updateTrails(cell: RegionCell, trails: TrailsDownload) =
        _state.update { all -> all[cell]?.let { all + (cell to it.copy(trails = trails)) } ?: all }

    private companion object {
        const val TAG = "RegionRepository"

        /** Bump when the overlay file format changes (v2: points, v3: huts, v4: all hiking points). */
        const val OVERLAY_SUFFIX = ".v4.geojson"
    }
}
