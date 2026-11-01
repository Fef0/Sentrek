package app.sentrek.maps

import app.sentrek.geo.overlay.OverlayLineSource
import app.sentrek.geo.overlay.OverlayLines
import app.sentrek.geo.LatLon
import android.content.Context
import android.util.Log
import app.sentrek.geo.BoundingBox
import app.sentrek.geo.DownloadSize
import app.sentrek.geo.RegionCell
import app.sentrek.geo.RegionGrid
import app.sentrek.geo.overlay.OverlayPoi
import app.sentrek.geo.overlay.OverlayPoiSource
import app.sentrek.geo.overlay.OverlayPois
import app.sentrek.map.MapEngine
import app.sentrek.map.MapStyle
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.offline.OfflineManager
import org.maplibre.android.offline.OfflineRegion
import org.maplibre.android.offline.OfflineRegionError
import org.maplibre.android.offline.OfflineRegionStatus
import org.maplibre.android.offline.OfflineTilePyramidRegionDefinition
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

sealed interface MapDownload {
    data object Queued : MapDownload
    /** [bytes] downloaded so far. */
    data class Downloading(val fraction: Float, val bytes: Long = 0) : MapDownload
    data class Ready(val bytes: Long) : MapDownload
}

/**
 * The trail details of an area (difficulty, huts, springs…), fetched one area at a time:
 * [Waiting] for another area's turn, [Downloading] now, [Retrying] after a busy server.
 */
enum class TrailsDownload {
    Missing, Waiting, Downloading, Retrying, Ready, Failed;

    val inProgress: Boolean get() = this == Waiting || this == Downloading || this == Retrying
}

/**
 * [updatedAt]: when the map was last downloaded (epoch ms), once it's there.
 * [failedUpdates]: updates in a row that didn't make it (map or trails), until one does.
 */
data class RegionState(
    val cell: RegionCell,
    val map: MapDownload,
    val trails: TrailsDownload,
    val updatedAt: Long? = null,
    val failedUpdates: Int = 0,
) {
    /** Worth telling the user: the area keeps its old map, but may need a better connection. */
    val updateFailing: Boolean get() = failedUpdates >= FAILURES_TO_WARN

    /** Paths, huts and springs change: worth downloading again after [after] ms. */
    fun isOld(now: Long, after: Long = OLD_AFTER_MS): Boolean =
        map is MapDownload.Ready && updatedAt != null && now - updatedAt > after

    companion object {
        /** When the user is reminded. */
        const val OLD_AFTER_MS = 180L * 24 * 60 * 60 * 1000

        /** When automatic updates download an area again: OpenFreeMap and Overpass are shared. */
        const val AUTO_UPDATE_AFTER_MS = 30L * 24 * 60 * 60 * 1000

        const val FAILURES_TO_WARN = 3
    }
}

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
) : OverlayPoiSource, OverlayLineSource {
    private val offline = OfflineManager.getInstance(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val trailsDir = File(context.filesDir, "trails").apply {
        mkdirs()
        // Older formats are refetched: restore() treats a missing file as "to download".
        listFiles { f -> !f.name.endsWith(OVERLAY_SUFFIX) }?.forEach { it.delete() }
    }
    private val trailsLock = Mutex()

    /** One file per downloaded area, holding when its map was downloaded. */
    private val datesDir = File(context.filesDir, "regions").apply { mkdirs() }

    /** Areas being updated in this session whose outcome isn't counted yet. */
    private val updating = HashSet<RegionCell>()

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

    /** Hiking points of the downloaded areas in [box], e.g. for the places along a route. */
    override suspend fun pois(box: BoundingBox): List<OverlayPoi> = withContext(Dispatchers.IO) {
        // From the files themselves: at startup (a route followed when the app was closed)
        // the regions may not be listed yet.
        val files = trailsDir.listFiles { f -> f.name.endsWith(OVERLAY_SUFFIX) }.orEmpty()
        files.filter { f -> RegionCell.parse(f.name.removeSuffix(OVERLAY_SUFFIX))?.bbox?.intersects(box) == true }
            .flatMap { file ->
                runCatching { file.bufferedReader().use { OverlayPois.read(it, box) } }
                    .onFailure { Log.w(TAG, "Cannot read points of ${file.name}", it) }
                    .getOrDefault(emptyList())
            }
    }

    /** Trail lines of the downloaded areas in [box]: the path network, for the turns on a route. */
    override suspend fun lines(box: BoundingBox): List<List<LatLon>> = withContext(Dispatchers.IO) {
        val files = trailsDir.listFiles { f -> f.name.endsWith(OVERLAY_SUFFIX) }.orEmpty()
        files.filter { f -> RegionCell.parse(f.name.removeSuffix(OVERLAY_SUFFIX))?.bbox?.intersects(box) == true }
            .flatMap { file ->
                runCatching { file.bufferedReader().use { OverlayLines.read(it, box) } }
                    .onFailure { Log.w(TAG, "Cannot read lines of ${file.name}", it) }
                    .getOrDefault(emptyList())
            }
    }

    fun download(cells: Collection<RegionCell>) {
        for (cell in cells) {
            if (cell in regions || cell in _state.value) continue
            put(RegionState(cell, MapDownload.Queued, trailsStateOnDisk(cell), failedUpdates = failures(cell)))
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

    /**
     * Brings [cells] up to date: today's map and trails. Nothing is lost if it fails: the map's
     * tiles are only marked stale, then fetched again one by one (the old one stays usable
     * until its replacement is in, and an unchanged tile costs a "not modified"); the trails
     * in use stay until the new ones are in.
     *
     * What is already current isn't downloaded again: one small request tells which version
     * of the map OpenFreeMap serves, and trails less than a week old are kept. [onUpToDate]
     * when that's every area.
     */
    fun update(cells: Collection<RegionCell>, onUpToDate: () -> Unit = {}) {
        val ready = cells.filter { _state.value[it]?.map is MapDownload.Ready && it in regions }
        if (ready.isEmpty()) return
        scope.launch {
            val current = tileVersion()
            val now = System.currentTimeMillis()
            var upToDate = 0
            for (cell in ready) {
                val state = _state.value[cell] ?: continue
                val region = regions[cell] ?: continue
                val mapCurrent = current != null && current == savedVersion(cell)
                val trailsCurrent = state.trails == TrailsDownload.Ready && now - trailFile(cell).lastModified() < TRAILS_FRESH_MS
                if (mapCurrent && trailsCurrent) {
                    // Checked today: counts as updated, for the reminders and automatic updates.
                    updateMap(cell, state.map, stamp(cell))
                    if (state.failedUpdates > 0) forgetFailedUpdates(listOf(cell))
                    upToDate++
                    continue
                }
                updating += cell
                if (!mapCurrent) {
                    region.invalidate(object : OfflineRegion.OfflineRegionInvalidateCallback {
                        override fun onInvalidate() {
                            queue.addLast(cell)
                            pump()
                        }

                        override fun onError(error: String) {
                            Log.e(TAG, "Cannot update region ${cell.id}: $error")
                            failUpdate(cell)
                        }
                    })
                }
                if (!trailsCurrent) fetchTrails(cell)
            }
            if (upToDate == ready.size) onUpToDate() else ensureService()
        }
    }

    fun delete(cells: Collection<RegionCell>) {
        for (cell in cells) {
            queue.remove(cell)
            trailFile(cell).delete()
            dateFile(cell).delete()
            failuresFile(cell).delete()
            versionFile(cell).delete()
            updating -= cell
            val region = regions.remove(cell)
            if (active == cell) {
                region?.setDownloadState(OfflineRegion.STATE_INACTIVE)
                active = null
            }
            remove(cell)
            region?.delete(object : OfflineRegion.OfflineRegionDeleteCallback {
                // MapLibre keeps a deleted region's tiles as browsing cache (up to 50 MB), so
                // deleting freed nothing and downloading the area again "finished" instantly.
                override fun onDelete() = offline.clearAmbientCache(null)
                override fun onError(error: String) {
                    Log.e(TAG, "Cannot delete region ${cell.id}: $error")
                }
            })
        }
        pump()
    }

    /** The user has been told: the count starts over. */
    fun forgetFailedUpdates(cells: Collection<RegionCell>) {
        for (cell in cells) {
            failuresFile(cell).delete()
            _state.update { all -> all[cell]?.let { all + (cell to it.copy(failedUpdates = 0)) } ?: all }
        }
    }

    /**
     * About how many bytes downloading [cells] takes, map and trail details, learning the
     * size of a tile from the areas already downloaded.
     */
    fun estimatedBytes(cells: Collection<RegionCell>): Long {
        val known = _state.value.values.mapNotNull { s -> (s.map as? MapDownload.Ready)?.bytes?.takeIf { it > 0 }?.let { it to s.cell.tileCount() } }
        return DownloadSize.estimate(
            newTiles = cells.sumOf { it.tileCount() },
            areas = cells.size,
            knownBytes = known.sumOf { it.first },
            knownTiles = known.sumOf { it.second },
        )
    }

    /** Space left for downloads. */
    fun freeBytes(): Long = context.filesDir.usableSpace

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
            put(RegionState(cell, MapDownload.Queued, trailsStateOnDisk(cell), failedUpdates = failures(cell)))
            if (!trailFile(cell).exists()) {
                fetchTrails(cell)
                ensureService()
            }
            region.getStatus(object : OfflineRegion.OfflineRegionStatusCallback {
                override fun onStatus(status: OfflineRegionStatus?) {
                    if (status?.isComplete == true) {
                        // Areas from before dates were kept count from now.
                        val date = downloadedAt(cell) ?: stamp(cell)
                        updateMap(cell, MapDownload.Ready(status.completedResourceSize), date)
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
        // The version MapLibre is about to download, for the next update to compare with.
        val version = scope.async { tileVersion() }
        region.setObserver(object : OfflineRegion.OfflineRegionObserver {
            private var shownPercent = -1

            override fun onStatusChanged(status: OfflineRegionStatus) {
                if (active != cell) return
                if (status.isComplete) {
                    region.setDownloadState(OfflineRegion.STATE_INACTIVE)
                    region.setObserver(null)
                    scope.launch { version.await()?.let { saveVersion(cell, it) } }
                    updateMap(cell, MapDownload.Ready(status.completedResourceSize), stamp(cell))
                    settleUpdate(cell)
                    active = null
                    pump()
                } else {
                    // Called for every tile: the screens only hear of each whole percent.
                    val required = status.requiredResourceCount.coerceAtLeast(1)
                    val percent = (status.completedResourceCount * 100 / required).toInt()
                    if (percent == shownPercent) return
                    shownPercent = percent
                    updateMap(cell, MapDownload.Downloading(status.completedResourceCount.toFloat() / required, status.completedResourceSize))
                }
            }

            // MapLibre retries failed requests by itself; for an update, one counts as a
            // failure unless the download still completes (settleUpdate starts the count over).
            override fun onError(error: OfflineRegionError) {
                Log.w(TAG, "Region ${cell.id}: ${error.reason} ${error.message}")
                failUpdate(cell)
            }

            override fun mapboxTileCountLimitExceeded(limit: Long) = Unit
        })
        updateMap(cell, MapDownload.Downloading(0f))
        region.setDownloadState(OfflineRegion.STATE_ACTIVE)
    }

    private fun fetchTrails(cell: RegionCell) {
        updateTrails(cell, TrailsDownload.Waiting)
        scope.launch {
            trailsLock.withLock {
                if (cell !in _state.value) return@withLock
                updateTrails(cell, TrailsDownload.Downloading)
                val result = runCatching {
                    trailSource.fetch(cell.bbox, trailFile(cell)) { busy ->
                        updateTrails(cell, if (busy) TrailsDownload.Retrying else TrailsDownload.Downloading)
                    }
                }
                result.exceptionOrNull()?.let { Log.w(TAG, "Trails for ${cell.id} failed", it) }
                if (cell !in _state.value) {
                    trailFile(cell).delete() // Area deleted while downloading.
                } else {
                    updateTrails(cell, if (result.isSuccess) TrailsDownload.Ready else TrailsDownload.Failed)
                    if (result.isSuccess) settleUpdate(cell) else failUpdate(cell)
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

    private fun updateMap(cell: RegionCell, map: MapDownload, updatedAt: Long? = null) =
        _state.update { all -> all[cell]?.let { all + (cell to it.copy(map = map, updatedAt = updatedAt ?: it.updatedAt)) } ?: all }

    private fun dateFile(cell: RegionCell) = File(datesDir, cell.id)

    private fun downloadedAt(cell: RegionCell): Long? =
        runCatching { dateFile(cell).readText().trim().toLong() }.getOrNull()

    /** Records that [cell]'s map was downloaded now, and returns when that is. */
    private fun stamp(cell: RegionCell): Long {
        val now = System.currentTimeMillis()
        runCatching { dateFile(cell).writeText(now.toString()) }.onFailure { Log.w(TAG, "Cannot date ${cell.id}", it) }
        return now
    }

    /** The map version [cell] was downloaded at: OpenFreeMap's tile address, which names it. */
    private fun versionFile(cell: RegionCell) = File(datesDir, cell.id + ".tiles")

    private fun savedVersion(cell: RegionCell): String? =
        runCatching { versionFile(cell).readText().trim() }.getOrNull()?.takeIf { it.isNotEmpty() }

    private fun saveVersion(cell: RegionCell, version: String) {
        runCatching { versionFile(cell).writeText(version) }.onFailure { Log.w(TAG, "Cannot save version of ${cell.id}", it) }
    }

    /**
     * The map version served now, from the map's TileJSON (about 20 KB): its tile address
     * changes with each new build of the map. Null when it can't be told (no network).
     */
    private suspend fun tileVersion(): String? = withContext(Dispatchers.IO) {
        runCatching {
            MapEngine.httpClient.newCall(Request.Builder().url(TILEJSON_URL).build()).execute().use { response ->
                if (!response.isSuccessful) return@use null
                JSONObject(response.body!!.string()).getJSONArray("tiles").getString(0)
            }
        }.onFailure { Log.w(TAG, "Cannot tell the map version", it) }.getOrNull()
    }

    private fun failuresFile(cell: RegionCell) = File(datesDir, cell.id + ".failed")

    private fun failures(cell: RegionCell): Int =
        runCatching { failuresFile(cell).readText().trim().toInt() }.getOrDefault(0)

    /** Counts a failed update of [cell], once per attempt. */
    private fun failUpdate(cell: RegionCell) {
        if (!updating.remove(cell)) return
        val count = failures(cell) + 1
        runCatching { failuresFile(cell).writeText(count.toString()) }
        _state.update { all -> all[cell]?.let { all + (cell to it.copy(failedUpdates = count)) } ?: all }
    }

    /** Map and trails both new: whatever failed before is over. */
    private fun settleUpdate(cell: RegionCell) {
        val state = _state.value[cell] ?: return
        if (state.map !is MapDownload.Ready || state.trails != TrailsDownload.Ready) return
        updating -= cell
        if (state.failedUpdates > 0 || failuresFile(cell).exists()) forgetFailedUpdates(listOf(cell))
    }

    private fun updateTrails(cell: RegionCell, trails: TrailsDownload) =
        _state.update { all -> all[cell]?.let { all + (cell to it.copy(trails = trails)) } ?: all }

    private companion object {
        const val TAG = "RegionRepository"

        /** Bump when the overlay file format changes (v2: points, v3: huts, v4: all hiking points, v5: all tags, v6: all paths and tracks, v9: waymarked routes, v10: route details). */
        const val OVERLAY_SUFFIX = ".v10.geojson"

        /** The vector source of the hiking style (assets/styles/hiking.json). */
        const val TILEJSON_URL = "https://tiles.openfreemap.org/planet"

        /** Trail details younger than this aren't fetched again: Overpass is shared. */
        const val TRAILS_FRESH_MS = 7L * 24 * 60 * 60 * 1000
    }
}
