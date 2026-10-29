package app.sentrek.geo

import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadSizeTest {
    @Test
    fun `typical tile size until the user has downloaded enough`() {
        assertEquals(20_300_000L, DownloadSize.estimate(newTiles = 1_000, areas = 1))
        assertEquals(20_300_000L, DownloadSize.estimate(newTiles = 1_000, areas = 1, knownBytes = 5_000_000, knownTiles = 100))
    }

    @Test
    fun `learns the tile size from downloaded areas`() {
        // 30 KB a tile so far: a mountain area with a lot of relief.
        assertEquals(60_600_000L, DownloadSize.estimate(newTiles = 2_000, areas = 2, knownBytes = 30_000_000, knownTiles = 1_000))
    }

    @Test
    fun `a real area has about a thousand tiles`() {
        val alps = RegionGrid.cellAt(LatLon(45.9, 7.7)).tileCount()
        assert(alps in 800..1_500) { "$alps tiles" }
    }
}
