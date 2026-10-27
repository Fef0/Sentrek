package app.sentrek.maps

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import app.sentrek.geo.BoundingBox
import app.sentrek.geo.MapFileInfo
import app.sentrek.geo.PmTilesHeader
import app.sentrek.map.RasterOverlay
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

enum class MapFileFormat(val extension: String) { MbTiles("mbtiles"), PmTiles("pmtiles") }

data class ImportedMap(val name: String, val file: File, val format: MapFileFormat, val info: MapFileInfo) {
    val id: String get() = file.nameWithoutExtension

    val overlay: RasterOverlay
        get() = RasterOverlay(
            id,
            when (format) {
                MapFileFormat.MbTiles -> "mbtiles://${file.absolutePath}"
                MapFileFormat.PmTiles -> "pmtiles://file://${file.absolutePath}"
            },
        )
}

sealed interface ImportResult {
    data class Imported(val map: ImportedMap) : ImportResult
    /** Only raster (image) maps can be shown for now. */
    data object VectorNotSupported : ImportResult
    data object UnknownFormat : ImportResult
    data object Failed : ImportResult
}

/** Map files the user imported (raster MBTiles / PMTiles), copied into app storage. */
@Singleton
class ImportedMapsRepository @Inject constructor(@ApplicationContext private val context: Context) {
    private val dir = File(context.filesDir, "imports").apply { mkdirs() }
    private val _maps = MutableStateFlow<List<ImportedMap>>(emptyList())
    val maps: StateFlow<List<ImportedMap>> = _maps.asStateFlow()

    init {
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch { reload() }
    }

    suspend fun import(uri: Uri): ImportResult = withContext(Dispatchers.IO) {
        val displayName = displayName(uri) ?: "map"
        val format = MapFileFormat.entries.firstOrNull { displayName.endsWith(".${it.extension}", ignoreCase = true) }
            ?: return@withContext ImportResult.UnknownFormat

        val base = displayName.substringBeforeLast('.').replace(Regex("[^A-Za-z0-9._-]"), "_").ifEmpty { "map" }
        val target = generateSequence(0) { it + 1 }
            .map { n -> File(dir, (if (n == 0) base else "$base-$n") + ".${format.extension}") }
            .first { !it.exists() }
        val tmp = File(dir, target.name + ".tmp")

        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                tmp.outputStream().use { input.copyTo(it, bufferSize = 1 shl 16) }
            } ?: throw IOException("Cannot open $uri")
            val info = inspect(tmp, format)
            if (!info.raster) {
                tmp.delete()
                return@withContext ImportResult.VectorNotSupported
            }
            if (!tmp.renameTo(target)) throw IOException("Cannot store ${target.name}")
            reload()
            ImportResult.Imported(ImportedMap(displayName.substringBeforeLast('.'), target, format, info))
        } catch (e: Exception) {
            Log.w(TAG, "Import of $displayName failed", e)
            tmp.delete()
            ImportResult.Failed
        }
    }

    suspend fun delete(map: ImportedMap) = withContext(Dispatchers.IO) {
        map.file.delete()
        reload()
    }

    private fun reload() {
        _maps.value = dir.listFiles().orEmpty()
            .mapNotNull { file ->
                val format = MapFileFormat.entries.firstOrNull { file.extension == it.extension } ?: return@mapNotNull null
                runCatching { ImportedMap(file.nameWithoutExtension, file, format, inspect(file, format)) }
                    .onFailure { Log.w(TAG, "Unreadable map file ${file.name}", it) }
                    .getOrNull()
            }
            .sortedBy { it.name.lowercase() }
    }

    private fun displayName(uri: Uri): String? =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: uri.lastPathSegment

    private fun inspect(file: File, format: MapFileFormat): MapFileInfo = when (format) {
        MapFileFormat.PmTiles -> file.inputStream().use(PmTilesHeader::read)
        MapFileFormat.MbTiles -> readMbTiles(file)
    }

    /** MBTiles keeps its description in a `metadata` name/value table. */
    private fun readMbTiles(file: File): MapFileInfo {
        val meta = HashMap<String, String>()
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT name, value FROM metadata", null).use { c ->
                while (c.moveToNext()) meta[c.getString(0)] = c.getString(1)
            }
        }
        val bounds = meta["bounds"]?.split(',')?.mapNotNull { it.trim().toDoubleOrNull() }
            ?.takeIf { it.size == 4 }
            ?.let { (w, s, e, n) -> runCatching { BoundingBox(s, w, n, e) }.getOrNull() }
        return MapFileInfo(
            raster = meta["format"]?.lowercase() in setOf("png", "jpg", "jpeg", "webp"),
            minZoom = meta["minzoom"]?.toIntOrNull() ?: 0,
            maxZoom = meta["maxzoom"]?.toIntOrNull() ?: 22,
            bounds = bounds,
        )
    }

    private companion object {
        const val TAG = "ImportedMaps"
    }
}
