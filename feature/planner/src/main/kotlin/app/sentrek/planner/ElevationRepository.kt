package app.sentrek.planner

import android.content.Context
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
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Terrain heights from the Terrarium tiles the hillshade uses. Tiles are kept in an HTTP
 * disk cache, so a route planned once can be replanned in the same area offline.
 */
@Singleton
class ElevationRepository @Inject constructor(@ApplicationContext context: Context) {
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

    /** Network first (the cache revalidates), then whatever is cached when offline. */
    private fun load(key: Terrarium.TileKey): Terrarium.Tile? {
        val url = "https://s3.amazonaws.com/elevation-tiles-prod/terrarium/${key.zoom}/${key.x}/${key.y}.png"
        val bytes = fetch(Request.Builder().url(url).build())
            ?: fetch(Request.Builder().url(url).cacheControl(CacheControl.FORCE_CACHE).build())
            ?: return null
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        if (bitmap.width != Terrarium.TILE_SIZE || bitmap.height != Terrarium.TILE_SIZE) return null
        val pixels = IntArray(Terrarium.TILE_SIZE * Terrarium.TILE_SIZE)
        bitmap.getPixels(pixels, 0, Terrarium.TILE_SIZE, 0, 0, Terrarium.TILE_SIZE, Terrarium.TILE_SIZE)
        bitmap.recycle()
        return Terrarium.Tile(FloatArray(pixels.size) { Terrarium.decode(pixels[it]) })
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
        private const val CACHE_BYTES = 100L * 1024 * 1024
        // 256 KB each.
        private const val MEMORY_TILES = 24
    }
}
