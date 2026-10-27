package app.sentrek.record

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.graphics.BitmapFactory
import android.util.Log
import android.util.LruCache
import app.sentrek.geo.LatLon
import app.sentrek.geo.dem.Terrarium
import app.sentrek.map.MapEngine
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Cache
import okhttp3.CacheControl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.zip.InflaterInputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Terrain heights from the Terrarium tiles the hillshade uses. In downloaded areas they come
 * from MapLibre's offline database, which already holds them; elsewhere from the network,
 * kept in an HTTP disk cache, so a route planned once can be replanned there offline.
 */
@Singleton
class ElevationRepository @Inject constructor(@ApplicationContext private val context: Context) {
    private val client: OkHttpClient by lazy {
        MapEngine.httpClient.newBuilder()
            .cache(Cache(File(context.cacheDir, "dem"), CACHE_BYTES))
            .build()
    }
    private val tiles = LruCache<Terrarium.TileKey, Terrarium.Tile>(MEMORY_TILES)
    private val missing = mutableSetOf<Terrarium.TileKey>()
    private val lock = Mutex()

    /** [points] with their terrain height; a point keeps no height where the tile can't be had. */
    suspend fun withElevation(points: List<LatLon>): List<LatLon> = withContext(Dispatchers.IO) {
        points.map { p ->
            val sample = Terrarium.locate(p.lat, p.lon)
            val tile = tile(sample.key) ?: return@map p.copy(ele = null)
            p.copy(ele = Terrarium.height(tile, sample).toDouble())
        }
    }

    private suspend fun tile(key: Terrarium.TileKey): Terrarium.Tile? = lock.withLock {
        tiles.get(key)?.let { return it }
        if (key in missing) return null
        val tile = load(key)
        if (tile != null) tiles.put(key, tile) else missing += key
        tile
    }

    /** Downloaded areas first, then the network (the cache revalidates), then the cache alone. */
    private fun load(key: Terrarium.TileKey): Terrarium.Tile? {
        val url = URL_TEMPLATE.replace("{z}", "${key.zoom}").replace("{x}", "${key.x}").replace("{y}", "${key.y}")
        val bytes = downloaded(key)
            ?: fetch(Request.Builder().url(url).build())
            ?: fetch(Request.Builder().url(url).cacheControl(CacheControl.FORCE_CACHE).build())
            ?: return null
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        if (bitmap.width != Terrarium.TILE_SIZE || bitmap.height != Terrarium.TILE_SIZE) return null
        val pixels = IntArray(Terrarium.TILE_SIZE * Terrarium.TILE_SIZE)
        bitmap.getPixels(pixels, 0, Terrarium.TILE_SIZE, 0, 0, Terrarium.TILE_SIZE, Terrarium.TILE_SIZE)
        bitmap.recycle()
        return Terrarium.Tile(FloatArray(pixels.size) { Terrarium.decode(pixels[it]) })
    }

    /**
     * The tile from MapLibre's offline database (areas downloaded on the Maps screen), read-only.
     * MapLibre zlib-compresses most tiles there. Null when it isn't there or the database is busy.
     */
    private fun downloaded(key: Terrarium.TileKey): ByteArray? {
        val file = File(context.filesDir, OFFLINE_DB)
        if (!file.exists()) return null
        return try {
            SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                db.rawQuery(
                    "SELECT data, compressed FROM tiles WHERE url_template = ? AND z = ? AND x = ? AND y = ? LIMIT 1",
                    arrayOf(URL_TEMPLATE, "${key.zoom}", "${key.x}", "${key.y}"),
                ).use { c ->
                    if (!c.moveToFirst()) return null
                    val data = c.getBlob(0) ?: return null
                    if (c.getInt(1) != 0) InflaterInputStream(data.inputStream()).use { it.readBytes() } else data
                }
            }
        } catch (e: Exception) {
            // Locked while MapLibre writes, or a schema we don't know: the network may still have it.
            Log.d(TAG, "No downloaded terrain tile $key: ${e.message}")
            null
        }
    }

    private fun fetch(request: Request): ByteArray? = try {
        client.newCall(request).execute().use { response ->
            if (response.isSuccessful) response.body?.bytes() else null
        }
    } catch (e: IOException) {
        Log.d(TAG, "No terrain tile ${request.url}: ${e.message}")
        null
    }

    /** Lets a tile that failed (e.g. offline) be tried again. */
    suspend fun retryMissing() = lock.withLock { missing.clear() }

    companion object {
        private const val TAG = "ElevationRepository"

        /** As in the map style (hiking.json), so the offline database lookup finds MapLibre's rows. */
        private const val URL_TEMPLATE = "https://s3.amazonaws.com/elevation-tiles-prod/terrarium/{z}/{x}/{y}.png"

        /** MapLibre's default offline database, in the app's files directory. */
        private const val OFFLINE_DB = "mbgl-offline.db"
        private const val CACHE_BYTES = 100L * 1024 * 1024
        // 256 KB each.
        private const val MEMORY_TILES = 24
    }
}
