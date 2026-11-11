package app.sentrek.maps

import app.sentrek.geo.RegionCell
import app.sentrek.geo.overlay.MapKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DownloadStatusTest {
    private fun area(
        n: Int,
        map: MapDownload,
        trails: TrailsDownload = TrailsDownload.Ready,
        extras: Map<MapKind, TrailsDownload> = emptyMap(),
    ) = RegionState(RegionCell(n, n), map, trails, extras = extras)

    private val ready = MapDownload.Ready(1000)

    @Test
    fun `nothing pending is no status`() {
        assertNull(downloadStatus(listOf(area(1, ready)), online = true))
        assertNull(downloadStatus(emptyList(), online = true))
        // Failed details aren't pending: Retry, or the connection coming back, starts them.
        assertNull(downloadStatus(listOf(area(1, ready, TrailsDownload.Failed)), online = true))
    }

    @Test
    fun `the map of one area, then its trails`() {
        val map = downloadStatus(listOf(area(1, MapDownload.Downloading(0.5f, 2048), TrailsDownload.Waiting)), online = true)!!
        assertEquals(DownloadStatus.Step.Map, map.step)
        assertEquals(2048L, map.mapBytes)
        // The map is four parts of five: half of it is 40% of the area.
        assertEquals(0.4f, map.fraction, 1e-4f)

        val trails = downloadStatus(listOf(area(1, ready, TrailsDownload.Downloading)), online = true)!!
        assertEquals(DownloadStatus.Step.Trails, trails.step)
        assertEquals(0.8f, trails.fraction, 1e-4f)
        assertEquals(80, trails.percent)
    }

    @Test
    fun `a batch counts its areas done`() {
        val status = downloadStatus(
            listOf(
                area(1, ready),
                area(2, MapDownload.Downloading(0f), TrailsDownload.Waiting),
                area(3, MapDownload.Queued, TrailsDownload.Waiting),
            ),
            online = true,
        )!!
        assertEquals(3, status.areas)
        assertEquals(1, status.done)
        assertEquals(1f / 3, status.fraction, 1e-4f)
    }

    @Test
    fun `pistes and cycle routes are steps of their own`() {
        val pistes = downloadStatus(listOf(area(1, ready, extras = mapOf(MapKind.Winter to TrailsDownload.Downloading))), online = true)!!
        assertEquals(DownloadStatus.Step.Pistes, pistes.step)
        val cycling = downloadStatus(listOf(area(1, ready, extras = mapOf(MapKind.Cycling to TrailsDownload.Retrying))), online = true)!!
        assertEquals(DownloadStatus.Step.Cycling, cycling.step)
        assertEquals(DownloadStatus.Waiting.Server, cycling.waiting)
    }

    @Test
    fun `what it waits for`() {
        val offline = downloadStatus(listOf(area(1, MapDownload.Downloading(0.2f))), online = false)!!
        assertEquals(DownloadStatus.Waiting.Network, offline.waiting)
        // A busy trail server while a map downloads: the map is what's going on.
        val busy = downloadStatus(listOf(area(1, MapDownload.Downloading(0.2f)), area(2, ready, TrailsDownload.Retrying)), online = true)!!
        assertNull(busy.waiting)
        assertEquals(DownloadStatus.Step.Map, busy.step)
    }

    @Test
    fun `never 100 percent before it's over`() {
        val status = downloadStatus(listOf(area(1, MapDownload.Downloading(0.9999f))), online = true)!!
        assertEquals(99, status.percent)
    }

    @Test
    fun `an area removed for failing still counts in the batch`() {
        // Three asked for: one removed, one done, one still downloading.
        val status = downloadStatus(listOf(area(1, ready), area(2, MapDownload.Downloading(0.5f))), online = true, failed = 1)!!
        assertEquals(3, status.areas)
        assertEquals(2, status.done)
        // The bar doesn't go back when an area is removed: it counts as over.
        // Half the map and its trails in: (0.5 × 4 + 1) / 5 = 60% of that area.
        assertEquals((1f + 0.6f + 1f) / 3, status.fraction, 1e-4f)
    }
}
