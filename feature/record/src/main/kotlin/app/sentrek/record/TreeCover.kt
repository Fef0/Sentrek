package app.sentrek.record

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import android.util.LruCache
import app.sentrek.geo.LatLon
import app.sentrek.geo.overlay.VectorTile
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.zip.GZIPInputStream
import java.util.zip.InflaterInputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.tan

/**
 * Where trees shade the ground, from the map tiles of the downloaded areas: woods and forests,
 * orchards, wooded swamps. Single trees and rows of them aren't in the tiles, and scrub or
 * vineyards are too low to shade a path. Offline only: outside downloaded areas it can't say.
 */
@Singleton
class TreeCover @Inject constructor(@ApplicationContext private val context: Context) {
    private val tiles = LruCache<Long, List<VectorTile.Polygon>>(CACHED_TILES)

    /** Whether trees cover [p]; false where there's no downloaded tile to tell. */
    fun covers(p: LatLon): Boolean {
        val n = 1 shl ZOOM
        val fx = (p.lon + 180) / 360 * n
        val latRad = Math.toRadians(p.lat)
        val fy = (1 - ln(tan(latRad) + 1 / kotlin.math.cos(latRad)) / PI) / 2 * n
        val x = floor(fx).toInt()
        val y = floor(fy).toInt()
        val polygons = polygons(x, y)
        if (polygons.isEmpty()) return false
        val extent = polygons.first().extent
        val px = (fx - x) * extent
        val py = (fy - y) * extent
        return polygons.any { it.contains(px, py) }
    }

    private fun polygons(x: Int, y: Int): List<VectorTile.Polygon> {
        val key = x.toLong() shl 32 or (y.toLong() and 0xFFFFFFFFL)
        tiles.get(key)?.let { return it }
        val found = tile(x, y)?.let { bytes ->
            runCatching { VectorTile.polygons(bytes, LAYER, ::shades) }
                .onFailure { Log.w(TAG, "Unreadable tile $x/$y", it) }
                .getOrNull()
        }.orEmpty()
        tiles.put(key, found)
        return found
    }

    /** OpenMapTiles' landcover: trees that cast a shadow on the way. */
    private fun shades(tags: Map<String, String>): Boolean =
        tags["class"] == "wood" || tags["subclass"] in TREE_SUBCLASSES

    /** The vector tile from MapLibre's offline database, uncompressed; null when it isn't there. */
    private fun tile(x: Int, y: Int): ByteArray? {
        val file = File(context.filesDir, OFFLINE_DB)
        if (!file.exists()) return null
        return try {
            SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                // The address carries the map's version (it changes with each update): the newest.
                val t = db.rawQuery(
                    "SELECT url_template FROM tiles WHERE url_template LIKE ? ORDER BY url_template DESC LIMIT 1",
                    arrayOf("%{z}/{x}/{y}.pbf"),
                ).use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: return null
                db.rawQuery(
                    "SELECT data, compressed FROM tiles WHERE url_template = ? AND z = ? AND x = ? AND y = ? LIMIT 1",
                    arrayOf(t, "$ZOOM", "$x", "$y"),
                ).use { c ->
                    if (!c.moveToFirst()) return null
                    val data = c.getBlob(0) ?: return null
                    val raw = if (c.getInt(1) != 0) InflaterInputStream(data.inputStream()).use { it.readBytes() } else data
                    // Tile servers often send them gzipped, and MapLibre keeps them so.
                    if (raw.size > 2 && raw[0] == 0x1f.toByte() && raw[1] == 0x8b.toByte()) {
                        GZIPInputStream(raw.inputStream()).use { it.readBytes() }
                    } else {
                        raw
                    }
                }
            }
        } catch (e: Exception) {
            // Busy (MapLibre writing) or a schema we don't know: no trees known here, this time.
            Log.d(TAG, "No map tile $x/$y: ${e.message}")
            null
        }
    }

    private companion object {
        const val TAG = "TreeCover"
        const val OFFLINE_DB = "mbgl-offline.db"
        const val LAYER = "landcover"

        /** The most detailed tiles: forests drawn closest to how they are. */
        const val ZOOM = 14
        const val CACHED_TILES = 32
        val TREE_SUBCLASSES = setOf("forest", "wood", "orchard", "swamp", "mangrove")
    }
}
