package app.sentrek.record

import android.content.Context
import android.util.Log
import app.sentrek.geo.LatLon
import app.sentrek.geo.PmTiles
import app.sentrek.geo.avalanche.AvalancheRegions
import app.sentrek.geo.avalanche.AvalancheCanada
import app.sentrek.geo.avalanche.AvalancheUs
import app.sentrek.geo.avalanche.Bulletin
import app.sentrek.geo.avalanche.GeoAreas
import app.sentrek.map.MapEngine
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/** The bulletin in force now, and the next day's once it is out. */
data class Avalanche(val now: Bulletin, val next: Bulletin?)

sealed interface AvalancheResult {
    data class Shown(val avalanche: Avalanche) : AvalancheResult

    /** No bulletin here: outside the services' areas, or out of season. */
    data object None : AvalancheResult

    /** A service or the connection failed, and there's no bulletin saved for here. */
    data object Failed : AvalancheResult
}

/**
 * Avalanche bulletins: the European warning services' (EAWS, via avalanche.report), Avalanche
 * Canada's and the US avalanche centers' (avalanche.org), fetched
 * with the weather forecast. Where a place is comes from the services' area outlines, read a
 * tile at a time and kept; bulletins are kept too, so one looked up at home is still there
 * without signal, as long as it is in force. Bulletins come in the app's language when the
 * service publishes in it, in English or the service's own language otherwise.
 */
@Singleton
class AvalancheRepository @Inject constructor(@ApplicationContext private val context: Context) {
    private val dir by lazy { File(context.filesDir, "avalanche").apply { mkdirs() } }
    private val tilesDir by lazy { File(dir, "tiles").apply { mkdirs() } }
    private val bulletinsDir by lazy { File(dir, "bulletins").apply { mkdirs() } }
    private val lock = Mutex()

    /** Something couldn't be fetched during this lookup: an answer of "none" may be wrong then. */
    @Volatile private var trouble = false

    /** The bulletin for [at]. Never throws: whatever goes wrong is [AvalancheResult.Failed]. */
    suspend fun at(at: LatLon): AvalancheResult = withContext(Dispatchers.IO) {
        lock.withLock {
            trouble = false
            try {
                find(at)?.let { AvalancheResult.Shown(it) } ?: if (trouble) AvalancheResult.Failed else AvalancheResult.None
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "No bulletin", e)
                AvalancheResult.Failed
            }
        }
    }

    private fun find(at: LatLon): Avalanche? = when {
        at.lon in EUROPE_WEST..EUROPE_EAST && at.lat > EUROPE_SOUTH -> europe(at)
        at.lon in AMERICA_WEST..AMERICA_EAST && at.lat > AMERICA_SOUTH -> canada(at) ?: unitedStates(at)
        else -> null
    }

    /** The European services' bulletins (EAWS), in the app's language when they have it. */
    private fun europe(at: LatLon): Avalanche? {
        val now = System.currentTimeMillis()
        val today = ISO.format(Date(now))
        val tomorrow = ISO.format(Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, 1) }.time)
        val tile = tile(at) ?: return null
        val language = Locale.getDefault().language.takeIf { it.isNotEmpty() && it != "en" }
        for (region in AvalancheRegions.at(tile, at.lat, at.lon, today)) {
            val bulletins = listOf(today, tomorrow).mapNotNull { date -> bulletin(region, date, language) }
            // Bulletins change at 17:00 for the next day: today's may be over already late in the evening.
            val current = bulletins.firstOrNull { now in it.validFrom until it.validUntil }
                ?: bulletins.firstOrNull { it.validFrom == 0L && it.validUntil == 0L }
                ?: continue
            return Avalanche(current, bulletins.firstOrNull { it !== current && it.validFrom >= current.validUntil && it.validFrom > now })
        }
        return null
    }

    /**
     * Avalanche Canada's forecasts (Parks Canada's too), in French when the app is: the areas'
     * outlines kept for a week, all the forecasts in one file.
     */
    private fun canada(at: LatLon): Avalanche? {
        val language = if (Locale.getDefault().language == "fr") "fr" else "en"
        val areas = text("ca-areas-$language.json", AvalancheCanada.areasUrl(language), AREAS_FRESH_MS)
            ?.let { GeoAreas.read(it.reader()) } ?: return null
        val area = areas.at(at.lat, at.lon) ?: return null
        val products = text("ca-products-$language.json", AvalancheCanada.productsUrl(language), FRESH_MS) ?: return null
        val (today, next) = AvalancheCanada.parse(products.reader(), area.id) ?: return null
        return current(today, next)
    }

    /** The US avalanche centers: their zones with today's level, then the zone's own forecast. */
    private fun unitedStates(at: LatLon): Avalanche? {
        val zones = text("us-map.json", AvalancheUs.MAP_URL, FRESH_MS)?.let { GeoAreas.read(it.reader()) } ?: return null
        val zone = zones.at(at.lat, at.lon) ?: return null
        val center = zone.properties["center_id"] ?: return null
        val forecast = text("us-${zone.id}.json", AvalancheUs.forecastUrl(center, zone.id), FRESH_MS)
        val (today, next) = AvalancheUs.parse(forecast?.reader(), zone) ?: return null
        return current(today, next)
    }

    /** The one in force now: a forecast saved yesterday may be for a day that's over. */
    private fun current(today: Bulletin, next: Bulletin?): Avalanche? {
        val now = System.currentTimeMillis()
        fun inForce(b: Bulletin) = b.validUntil == 0L || now < b.validUntil
        return when {
            inForce(today) -> Avalanche(today, next?.takeIf { it.validFrom > now })
            next != null && inForce(next) -> Avalanche(next, null)
            else -> null
        }
    }

    /** A file of [url], fetched when the saved copy is older than [freshMs]; the saved copy offline. */
    private fun text(name: String, url: String, freshMs: Long): String? {
        val file = File(dir, name)
        if (!file.exists() || System.currentTimeMillis() - file.lastModified() > freshMs) {
            try {
                get(url)?.let { save(file, it) }
            } catch (e: IOException) {
                Log.d(TAG, "Cannot fetch $url: ${e.message}")
                trouble = true
            }
        }
        return file.takeIf { it.exists() }?.readText()
    }

    /** The bulletin of [region] for [date]: saved if fetched lately, else fetched; the saved one offline. */
    private fun bulletin(region: String, date: String, language: String?): Bulletin? {
        val known = File(dir, "service-$region").takeIf { it.exists() }?.readText()?.trim()?.takeIf { it.isNotEmpty() }
        val services = listOfNotNull(known) + AvalancheRegions.services(region).filter { it != known }
        for (service in services) {
            val file = File(bulletinsDir, "$date-$service-${language ?: "en"}.json")
            val missing = File(bulletinsDir, "$date-$service.none")
            val fresh = System.currentTimeMillis() - FRESH_MS
            if (missing.exists() && missing.lastModified() > fresh) continue
            if (!file.exists() || file.lastModified() <= fresh) {
                try {
                    // In the app's language first, then as published.
                    val body = language?.let { get(AvalancheRegions.bulletinUrl(service, date, it)) }
                        ?: get(AvalancheRegions.bulletinUrl(service, date, null))
                    if (body == null) {
                        if (!file.exists()) missing.writeText("")
                        continue
                    }
                    save(file, body)
                    missing.delete()
                } catch (e: IOException) {
                    Log.d(TAG, "Cannot fetch the bulletin: ${e.message}")
                    trouble = true
                    if (!file.exists()) continue
                }
            }
            val bulletin = runCatching { file.reader().use { Bulletin.parse(it, region) } }.getOrNull() ?: continue
            if (known != service) runCatching { File(dir, "service-$region").writeText(service) }
            return bulletin
        }
        return null
    }

    /** The regions tile at [at], kept once fetched; empty where there are none. */
    private fun tile(at: LatLon): ByteArray? {
        val (x, y) = AvalancheRegions.tile(at.lat, at.lon)
        val file = File(tilesDir, "${AvalancheRegions.ZOOM}-$x-$y.mvt")
        if (file.exists()) return file.readBytes()
        val head = head() ?: return null
        val header = PmTiles.header(head)
        var entries = PmTiles.directory(head.copyOfRange(header.rootOffset.toInt(), (header.rootOffset + header.rootLength).toInt()), header.internalCompression)
        val id = PmTiles.tileId(AvalancheRegions.ZOOM, x, y)
        var entry = PmTiles.find(entries, id)
        // A leaf directory to look in: at most a couple of levels.
        repeat(LEAF_LEVELS) {
            val leaf = entry?.takeIf { it.runLength == 0L } ?: return@repeat
            entries = PmTiles.directory(range(header.leafOffset + leaf.offset, leaf.length), header.internalCompression)
            entry = PmTiles.find(entries, id)
        }
        val found = entry?.takeIf { it.runLength > 0 }
        val bytes = if (found == null) {
            ByteArray(0)
        } else {
            PmTiles.decompress(range(header.dataOffset + found.offset, found.length), header.tileCompression)
        }
        save(file, bytes)
        return bytes
    }

    /**
     * The file's header and root directory, kept for a month: the areas are redrawn about once
     * a year. When they changed, the kept tiles are of the old file and are dropped.
     */
    private fun head(): ByteArray? {
        val file = File(dir, "regions-head.bin")
        val old = file.takeIf { it.exists() }?.readBytes()
        if (old != null && System.currentTimeMillis() - file.lastModified() < HEAD_FRESH_MS) return old
        val fetched = try {
            range(0, PmTiles.HEAD_BYTES)
        } catch (e: IOException) {
            Log.d(TAG, "Cannot fetch the areas: ${e.message}")
            if (old == null) trouble = true
            return old
        }
        if (old != null && !old.contentEquals(fetched)) tilesDir.listFiles()?.forEach { it.delete() }
        save(file, fetched)
        return fetched
    }

    /** [length] bytes of the regions file from [offset]. */
    private fun range(offset: Long, length: Int): ByteArray {
        val request = Request.Builder().url(AvalancheRegions.PMTILES_URL)
            .header("Range", "bytes=$offset-${offset + length - 1}")
            .build()
        return MapEngine.httpClient.newCall(request).execute().use { response ->
            if (response.code != 206 && response.code != 200) throw IOException("HTTP ${response.code}")
            val bytes = response.body?.bytes() ?: throw IOException("Empty answer")
            // A server ignoring the range sends it all: the part asked for is in it.
            if (response.code == 200) bytes.copyOfRange(offset.toInt().coerceAtMost(bytes.size), (offset + length).toInt().coerceAtMost(bytes.size)) else bytes
        }
    }

    /** The text at [url]; null when there is none (404, 410). A server error is an [IOException]. */
    private fun get(url: String): String? =
        MapEngine.httpClient.newCall(Request.Builder().url(url).build()).execute().use { response ->
            when {
                response.code == 404 || response.code == 410 -> null
                !response.isSuccessful -> throw IOException("HTTP ${response.code}")
                else -> response.body?.string()
            }
        }

    private fun save(file: File, bytes: ByteArray) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
        file.setLastModified(System.currentTimeMillis())
    }

    private fun save(file: File, text: String) {
        save(file, text.toByteArray())
        forgetOld()
    }

    /** Bulletins of past days are of no use. */
    private fun forgetOld() {
        val before = System.currentTimeMillis() - KEEP_MS
        bulletinsDir.listFiles()?.filter { it.lastModified() < before }?.forEach { it.delete() }
    }

    private companion object {
        const val TAG = "AvalancheRepository"

        /** Bulletins are updated in the morning and at 17:00: an hour old is still current. */
        const val FRESH_MS = 60 * 60 * 1000L
        const val HEAD_FRESH_MS = 30L * 24 * 60 * 60 * 1000
        const val AREAS_FRESH_MS = 7L * 24 * 60 * 60 * 1000

        /** Where each provider's areas are: the European services' map file, North America. */
        const val EUROPE_WEST = -25.0
        const val EUROPE_EAST = 45.0
        const val EUROPE_SOUTH = 34.0
        const val AMERICA_WEST = -170.0
        const val AMERICA_EAST = -50.0
        const val AMERICA_SOUTH = 30.0
        const val KEEP_MS = 3L * 24 * 60 * 60 * 1000
        const val LEAF_LEVELS = 3
        val ISO = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT)
    }
}
