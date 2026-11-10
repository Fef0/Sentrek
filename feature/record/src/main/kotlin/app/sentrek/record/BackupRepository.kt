package app.sentrek.record

import android.content.Context
import android.net.Uri
import android.util.Log
import app.sentrek.geo.place.Place
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Sentrek files: zips with tracks, places and their photos. A backup holds everything the
 * user made (downloaded maps and settings left out); a shared file holds one place or one
 * track. Opening either only adds what isn't on the phone already: nothing is ever replaced.
 */
@Singleton
class BackupRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val tracks: TrackRepository,
    private val places: PlaceRepository,
    private val photos: PhotoStore,
) {
    /** What a backup or a shared file added. */
    data class Restored(val tracks: List<SavedTrack>, val places: List<Place>)

    suspend fun backUp(target: Uri): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.openOutputStream(target)?.use { out ->
                val all = places.places.value
                val trackPhotos = tracks.tracks.value.flatMap { it.photos }
                write(out, tracks.directory.listFiles().orEmpty().filter { it.isFile }, all, all.flatMap { it.photos } + trackPhotos)
            } ?: error("No output stream")
        }.onFailure { Log.w(TAG, "Backup failed", it) }.isSuccess
    }

    /** One place with its photos and note, as a file to send; null when it can't be written. */
    suspend fun sharePlace(place: Place): File? = shareFile(place.name) { out ->
        write(out, emptyList(), listOf(place), place.photos)
    }

    /** One track with its photos, and the places saved while it was recorded with theirs. */
    suspend fun shareTrack(track: SavedTrack): File? = shareFile(track.name) { out ->
        val files = tracks.directory.listFiles { f -> f.isFile && f.name.substringBeforeLast('.') == track.id }.orEmpty().toList()
        val during = if (track.imported) emptyList() else places.savedBetween(track.startTime, track.endTime)
        write(out, files, during, track.photos + during.flatMap { it.photos })
    }

    /** True when [source] is a Sentrek file (a plain KMZ is a zip too). */
    suspend fun isSentrekFile(source: Uri): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.openInputStream(source)?.use { input ->
                ZipInputStream(input.buffered()).use { it.nextEntry?.name == MANIFEST }
            } ?: false
        }.getOrDefault(false)
    }

    /** Adds what's missing; null when the file isn't a Sentrek file or can't be read. */
    suspend fun restore(source: Uri): Restored? {
        if (!isSentrekFile(source)) return null
        var placesJson: String? = null
        val newTracks = mutableListOf<String>()
        val ok = withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.openInputStream(source)?.use { input ->
                    ZipInputStream(input.buffered()).use { zip ->
                        while (true) {
                            val entry = zip.nextEntry ?: break
                            val name = entry.name
                            when {
                                entry.isDirectory -> Unit
                                name == PLACES -> placesJson = String(readLimited(zip, MAX_JSON))
                                name.startsWith(TRACKS) -> {
                                    val file = name.removePrefix(TRACKS)
                                    if (copyIfMissing(zip, tracks.directory, file) && file.endsWith(SUMMARY)) newTracks += file.removeSuffix(SUMMARY)
                                }
                                name.startsWith(PHOTOS) -> copyIfMissing(zip, photos.directory, name.removePrefix(PHOTOS))
                            }
                        }
                    }
                } ?: error("No input stream")
            }.onFailure { Log.w(TAG, "Restore failed", it) }.isSuccess
        }
        if (!ok) return null
        val newPlaces = placesJson?.let { places.restore(it) }.orEmpty()
        tracks.reload()
        return Restored(newTracks.mapNotNull(tracks::find), newPlaces)
    }

    private suspend fun shareFile(name: String, write: (OutputStream) -> Unit): File? = withContext(Dispatchers.IO) {
        runCatching {
            val dir = File(context.cacheDir, "share").apply { deleteRecursively(); mkdirs() }
            val clean = name.replace(Regex("""[\\/:*?"<>|,]+"""), "-").trim('-', ' ').ifEmpty { "Sentrek" }
            File(dir, "$clean.zip").also { f -> f.outputStream().use(write) }
        }.onFailure { Log.w(TAG, "Cannot prepare $name for sharing", it) }.getOrNull()
    }

    private fun write(out: OutputStream, trackFiles: List<File>, placeList: List<Place>, photoNames: List<String>) {
        ZipOutputStream(out.buffered()).use { zip ->
            // First, so a Sentrek file is told from any other zip by its first entry.
            zip.putNextEntry(ZipEntry(MANIFEST))
            zip.write(JSONObject().put("app", "Sentrek").put("format", FORMAT).put("created", System.currentTimeMillis()).toString().toByteArray())
            zip.closeEntry()
            for (f in trackFiles) if (!f.name.endsWith(".tmp")) add(zip, f, TRACKS + f.name)
            if (placeList.isNotEmpty()) {
                zip.putNextEntry(ZipEntry(PLACES))
                zip.write(PlaceRepository.json(placeList).toByteArray())
                zip.closeEntry()
            }
            for (name in photoNames.distinct()) photos.file(name).takeIf { it.isFile }?.let { add(zip, it, PHOTOS + name) }
        }
    }

    private fun add(zip: ZipOutputStream, file: File, path: String) {
        zip.putNextEntry(ZipEntry(path))
        file.inputStream().use { it.copyTo(zip) }
        zip.closeEntry()
    }

    /**
     * Writes the entry as [name] in [dir] unless a file of that name is there. Only plain
     * names: a crafted zip must not write outside the folder ("../"). True when written.
     */
    private fun copyIfMissing(input: InputStream, dir: File, name: String): Boolean {
        if (!SAFE_NAME.matches(name) || name.all { it == '.' }) return false
        val target = File(dir, name)
        if (target.exists()) return false
        val tmp = File(dir, "$name.tmp")
        var total = 0L
        tmp.outputStream().use { out ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                total += n
                if (total > MAX_FILE) break
                out.write(buffer, 0, n)
            }
        }
        if (total > MAX_FILE) {
            tmp.delete()
            error("File too large: $name")
        }
        return tmp.renameTo(target)
    }

    private fun readLimited(input: InputStream, max: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            out.write(buffer, 0, n)
            require(out.size() <= max) { "Entry too large" }
        }
        return out.toByteArray()
    }

    companion object {
        private const val TAG = "BackupRepository"
        private const val FORMAT = 1
        private const val MANIFEST = "sentrek-backup.json"
        private const val PLACES = "places.json"
        private const val TRACKS = "tracks/"
        private const val PHOTOS = "photos/"
        private const val SUMMARY = ".json"
        private val SAFE_NAME = Regex("""[A-Za-z0-9._-]+""")
        private const val MAX_JSON = 32 * 1024 * 1024
        private const val MAX_FILE = 200L * 1024 * 1024

        /** "Sentrek backup 2026-10-28.zip" */
        fun fileName(now: Long = System.currentTimeMillis()): String =
            "Sentrek backup " + SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date(now)) + ".zip"
    }
}
