package app.sentrek.record

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.util.Log
import android.util.LruCache
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Photos of places, as JPEG files in `photos/`, named in [app.sentrek.geo.place.Place.photos].
 * Each is stored upright and at most [MAX_SIDE] px: a phone photo is 3-10 MB, this ~0.5 MB.
 */
@Singleton
class PhotoStore @Inject constructor(@ApplicationContext private val context: Context) {
    private val dir = File(context.filesDir, "photos").apply { mkdirs() }

    /** Small copies for lists and sheets, by name and size. */
    private val thumbnails = object : LruCache<String, Bitmap>(THUMBNAIL_CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    fun file(name: String) = File(dir, name)

    /** Where the photos are, for backups. */
    val directory: File get() = dir

    /** For other apps: the camera writing a new photo, or the share sheet reading one. */
    fun uri(name: String): Uri = FileProvider.getUriForFile(context, context.packageName + ".tracks", file(name))

    /** A name for the camera app to write to (see [finishCapture]). */
    fun newName(): String = UUID.randomUUID().toString() + ".jpg"

    /** The camera's full-size photo, made upright and small; false when there is none. */
    suspend fun finishCapture(name: String): Boolean = withContext(Dispatchers.IO) {
        val f = file(name)
        if (!f.exists() || f.length() == 0L) {
            f.delete()
            return@withContext false
        }
        runCatching { store(f.inputStream().use { it.readBytes() }, f) }
            .onFailure { Log.w(TAG, "Cannot store photo", it); f.delete() }
            .isSuccess
    }

    /** A copy of a photo picked from the gallery; its name, or null when unreadable. */
    suspend fun import(source: Uri): String? = withContext(Dispatchers.IO) {
        val name = newName()
        runCatching {
            val bytes = context.contentResolver.openInputStream(source)?.use { it.readBytes() } ?: error("No input")
            store(bytes, file(name))
            name
        }.onFailure { Log.w(TAG, "Cannot import photo", it); file(name).delete() }.getOrNull()
    }

    /** Copies of [names] under new names, for a copied route; the missing ones are left out. */
    suspend fun copy(names: List<String>): List<String> = withContext(Dispatchers.IO) {
        names.mapNotNull { name ->
            val copy = newName()
            runCatching { file(name).copyTo(file(copy)); copy }
                .onFailure { Log.w(TAG, "Cannot copy photo $name", it); file(copy).delete() }
                .getOrNull()
        }
    }

    fun delete(names: Collection<String>) {
        for (n in names) {
            file(n).delete()
            thumbnails.snapshot().keys.filter { it.startsWith("$n@") }.forEach(thumbnails::remove)
        }
    }

    /** About [side] px on its shorter side, for the screen; null when missing. */
    suspend fun thumbnail(name: String, side: Int): Bitmap? {
        val key = "$name@$side"
        thumbnails.get(key)?.let { return it }
        return withContext(Dispatchers.IO) {
            runCatching { decode(file(name), side) }.getOrNull()?.also { thumbnails.put(key, it) }
        }
    }

    private fun store(bytes: ByteArray, target: File) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Not an image" }
        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize(maxOf(bounds.outWidth, bounds.outHeight), MAX_SIDE) }
        var bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: error("Cannot decode")
        val scale = MAX_SIDE.toFloat() / maxOf(bitmap.width, bitmap.height)
        val matrix = Matrix().apply {
            if (scale < 1f) postScale(scale, scale)
            postRotate(rotation(bytes).toFloat())
        }
        if (!matrix.isIdentity) bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        // Temp file first: the camera's original stays until the small copy is complete.
        val tmp = File(target.path + ".tmp")
        tmp.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, QUALITY, it) }
        if (!tmp.renameTo(target)) {
            target.delete()
            tmp.renameTo(target)
        }
    }

    private fun decode(f: File, side: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, bounds)
        if (bounds.outWidth <= 0) return null
        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize(minOf(bounds.outWidth, bounds.outHeight), side) }
        return BitmapFactory.decodeFile(f.path, options)
    }

    /** Stored photos are upright already; this is for the camera's and the gallery's. */
    private fun rotation(bytes: ByteArray): Int = runCatching {
        when (ExifInterface(bytes.inputStream()).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }
    }.getOrDefault(0)

    private fun sampleSize(size: Int, wanted: Int): Int {
        var sample = 1
        while (size / (sample * 2) >= wanted) sample *= 2
        return sample
    }

    private companion object {
        const val TAG = "PhotoStore"
        const val MAX_SIDE = 1600
        const val QUALITY = 85
        const val THUMBNAIL_CACHE_BYTES = 16 * 1024 * 1024
    }
}
