package app.sentrek.record

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteDatabaseLockedException
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteReadOnlyDatabaseException
import android.graphics.BitmapFactory
import android.util.Log
import android.util.LruCache
import app.sentrek.geo.LatLon
import app.sentrek.geo.dem.TerrainTiles
import app.sentrek.geo.dem.Terrarium
import app.sentrek.map.MapEngine
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
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
class ElevationRepository @Inject constructor(@ApplicationContext private val context: Context) : TerrainTiles {
    private val client: OkHttpClient by lazy {
        MapEngine.httpClient.newBuilder()
            .cache(Cache(File(context.cacheDir, "dem"), CACHE_BYTES))
            .build()
    }
    private val tiles = LruCache<Terrarium.TileKey, Terrarium.Tile>(MEMORY_TILES)
    private val missing: MutableSet<Terrarium.TileKey> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /**
     * One tile is loaded once even when asked for twice at the same time; others go on: a
     * download stuck on a weak signal would otherwise hold up the contours of downloaded areas.
     */
    private val locks = Array(LOCKS) { Mutex() }

    private fun lockFor(key: Terrarium.TileKey) = locks[(key.hashCode() and Int.MAX_VALUE) % LOCKS]

    init {
        // Tiles missed offline are worth asking for again once a network is back.
        runCatching {
            context.getSystemService(ConnectivityManager::class.java)?.registerDefaultNetworkCallback(
                object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) = retryMissing()
                },
            )
        }.onFailure { Log.w(TAG, "Cannot watch the network", it) }
    }

    /** MapLibre's offline database, opened once; closed again when a read goes wrong. */
    @Volatile private var offlineDb: SQLiteDatabase? = null

    @Synchronized
    private fun offlineDb(file: File): SQLiteDatabase =
        offlineDb?.takeIf { it.isOpen } ?: SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY)
            .also { offlineDb = it }

    @Synchronized
    private fun closeOfflineDb() {
        runCatching { offlineDb?.close() }
        offlineDb = null
    }

    /** [points] with their terrain height; a point keeps no height where the tile can't be had. */
    suspend fun withElevation(points: List<LatLon>): List<LatLon> = withContext(Dispatchers.IO) {
        points.map { p ->
            val sample = Terrarium.locate(p.lat, p.lon)
            val tile = cachedTile(sample.key) ?: return@map p.copy(ele = null)
            p.copy(ele = Terrarium.height(tile, sample).toDouble())
        }
    }

    /** For the map's contour lines, on MapLibre's worker threads. */
    override fun tile(key: Terrarium.TileKey): Terrarium.Tile? = runBlocking { cachedTile(key) }

    private suspend fun cachedTile(key: Terrarium.TileKey): Terrarium.Tile? {
        tiles.get(key)?.let { return it }
        return lockFor(key).withLock { loadOnce(key) }
    }

    private fun loadOnce(key: Terrarium.TileKey): Terrarium.Tile? {
        tiles.get(key)?.let { return it }
        if (key in missing) return null
        val (tile, busy) = load(key)
        if (tile != null) tiles.put(key, tile) else if (!busy) missing += key
        return tile
    }

    /**
     * Downloaded areas first, then the cache (terrain tiles never change), then the network.
     * Also says whether the offline database was busy: then the tile is worth asking for again.
     */
    private fun load(key: Terrarium.TileKey): Pair<Terrarium.Tile?, Boolean> {
        val url = URL_TEMPLATE.replace("{z}", "${key.zoom}").replace("{x}", "${key.x}").replace("{y}", "${key.y}")
        var busy = false
        val bytes = try {
            downloaded(key)
        } catch (e: DatabaseBusy) {
            busy = true
            null
        }
            ?: fetch(Request.Builder().url(url).cacheControl(CacheControl.FORCE_CACHE).build())
            ?: fetch(Request.Builder().url(url).build())
            ?: return null to busy
        return decode(bytes) to false
    }

    private fun decode(bytes: ByteArray): Terrarium.Tile? {
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        if (bitmap.width != Terrarium.TILE_SIZE || bitmap.height != Terrarium.TILE_SIZE) return null
        val pixels = IntArray(Terrarium.TILE_SIZE * Terrarium.TILE_SIZE)
        bitmap.getPixels(pixels, 0, Terrarium.TILE_SIZE, 0, 0, Terrarium.TILE_SIZE, Terrarium.TILE_SIZE)
        bitmap.recycle()
        return Terrarium.Tile(FloatArray(pixels.size) { Terrarium.decode(pixels[it]) })
    }

    /**
     * The tile from MapLibre's offline database (areas downloaded on the Maps screen), read-only.
     * MapLibre zlib-compresses most tiles there. Null when it isn't there; [DatabaseBusy] when
     * MapLibre kept it busy (it writes its cache there too) through a few short tries.
     */
    private fun downloaded(key: Terrarium.TileKey): ByteArray? {
        val file = File(context.filesDir, OFFLINE_DB)
        if (!file.exists()) return null.also { closeOfflineDb() }
        repeat(DB_ATTEMPTS) {
            try {
                return query(file, key)
            } catch (e: SQLiteException) {
                if (e !is SQLiteReadOnlyDatabaseException && e !is SQLiteDatabaseLockedException) throw e
                // Mid-write, MapLibre's journal makes the file unreadable from here for a moment.
                Log.d(TAG, "Offline database busy for $key: ${e.message}")
                Thread.sleep(DB_RETRY_MS)
            }
        }
        throw DatabaseBusy()
    }

    private class DatabaseBusy : Exception()

    private fun query(file: File, key: Terrarium.TileKey): ByteArray? {
        return try {
            offlineDb(file).rawQuery(
                "SELECT data, compressed FROM tiles WHERE url_template = ? AND z = ? AND x = ? AND y = ? LIMIT 1",
                arrayOf(URL_TEMPLATE, "${key.zoom}", "${key.x}", "${key.y}"),
            ).use { c ->
                if (!c.moveToFirst()) return null
                val data = c.getBlob(0) ?: return null
                if (c.getInt(1) != 0) InflaterInputStream(data.inputStream()).use { it.readBytes() } else data
            }
        } catch (e: SQLiteReadOnlyDatabaseException) {
            throw e
        } catch (e: SQLiteDatabaseLockedException) {
            throw e
        } catch (e: Exception) {
            // A schema we don't know, or a file MapLibre replaced: opened afresh next time.
            Log.d(TAG, "No downloaded terrain tile $key: ${e.message}")
            closeOfflineDb()
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

    private val _retries = MutableStateFlow(0)

    /**
     * Goes up each time tiles that failed are let through again. The map keeps the empty
     * contours it drew for them until told to draw them again.
     */
    val retries: StateFlow<Int> = _retries.asStateFlow()

    /** Lets a tile that failed (e.g. offline) be tried again. */
    fun retryMissing() {
        if (missing.isEmpty()) return
        Log.d(TAG, "Trying ${missing.size} terrain tiles again")
        missing.clear()
        _retries.update { it + 1 }
    }

    companion object {
        private const val TAG = "ElevationRepository"

        /** As in the map style (hiking.json), so the offline database lookup finds MapLibre's rows. */
        private const val URL_TEMPLATE = "https://s3.amazonaws.com/elevation-tiles-prod/terrarium/{z}/{x}/{y}.png"

        /** MapLibre's default offline database, in the app's files directory. */
        private const val OFFLINE_DB = "mbgl-offline.db"
        private const val DB_ATTEMPTS = 3
        private const val DB_RETRY_MS = 150L
        private const val CACHE_BYTES = 100L * 1024 * 1024
        // 256 KB each. Contours need the tiles around the ones on screen too.
        private const val MEMORY_TILES = 48
        private const val LOCKS = 16
    }
}
