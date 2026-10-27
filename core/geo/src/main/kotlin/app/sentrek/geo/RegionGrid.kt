package app.sentrek.geo

import kotlin.math.floor

/**
 * A downloadable map area. The world is split into fixed [RegionGrid.CELL_DEG] × [RegionGrid.CELL_DEG]
 * cells (Trekarta-style), so a user downloads only the places they hike in.
 */
data class RegionCell(val row: Int, val col: Int) {
    val id: String get() = "c${row}_$col"

    val bbox: BoundingBox
        get() = BoundingBox(
            south = -90.0 + row * RegionGrid.CELL_DEG,
            west = -180.0 + col * RegionGrid.CELL_DEG,
            north = -90.0 + (row + 1) * RegionGrid.CELL_DEG,
            east = -180.0 + (col + 1) * RegionGrid.CELL_DEG,
        )

    fun tileCount(minZoom: Int = 0, maxZoom: Int = RegionGrid.MAX_DOWNLOAD_ZOOM): Long =
        TileMath.tileCount(bbox, minZoom, maxZoom)

    companion object {
        private val ID = Regex("""c(\d+)_(\d+)""")

        fun parse(id: String): RegionCell? =
            ID.matchEntire(id)?.destructured?.let { (r, c) -> RegionCell(r.toInt(), c.toInt()) }
    }
}

object RegionGrid {
    /**
     * 0.5° keeps each cell under OpenFreeMap's per-download limit of 3,000 tiles at z0–14
     * (≈1,000 tiles at 45°N).
     */
    const val CELL_DEG = 0.5
    const val MAX_DOWNLOAD_ZOOM = 14
    const val ROWS = (180 / CELL_DEG).toInt()
    const val COLS = (360 / CELL_DEG).toInt()

    fun cellAt(p: LatLon): RegionCell = RegionCell(
        row = floor((p.lat + 90.0) / CELL_DEG).toInt().coerceIn(0, ROWS - 1),
        col = floor((p.lon + 180.0) / CELL_DEG).toInt().coerceIn(0, COLS - 1),
    )

    /** All cells intersecting [box], row-major from south-west. */
    fun cellsIn(box: BoundingBox): List<RegionCell> {
        val sw = cellAt(LatLon(box.south, box.west))
        val ne = cellAt(LatLon(box.north, box.east))
        return (sw.row..ne.row).flatMap { r -> (sw.col..ne.col).map { c -> RegionCell(r, c) } }
    }
}
