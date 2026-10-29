package app.sentrek.geo

/**
 * How much a download of map areas will take, before it starts: map servers don't say. Tiles
 * times an average tile size, learnt from the areas already downloaded once there are enough
 * of them (terrain, towns and forests differ a lot), a typical value until then.
 */
object DownloadSize {
    /** About 20 MB for an area in the Alps, as measured with OpenFreeMap tiles and the terrain. */
    const val TYPICAL_BYTES_PER_TILE = 20_000.0

    /** Trail details: a few hundred KB for a dense alpine area. */
    const val TRAILS_BYTES = 300_000L

    /** Fewer tiles than this (about one area) say little about the next ones. */
    private const val MIN_LEARNT_TILES = 500L

    /**
     * Bytes for areas of [newTiles] tiles in all, given [knownBytes] stored for [knownTiles]
     * tiles in the areas already there, plus their trail details ([areas] of them).
     */
    fun estimate(newTiles: Long, areas: Int, knownBytes: Long = 0, knownTiles: Long = 0): Long {
        val perTile = if (knownTiles >= MIN_LEARNT_TILES && knownBytes > 0) knownBytes.toDouble() / knownTiles else TYPICAL_BYTES_PER_TILE
        return (newTiles * perTile).toLong() + areas * TRAILS_BYTES
    }
}
