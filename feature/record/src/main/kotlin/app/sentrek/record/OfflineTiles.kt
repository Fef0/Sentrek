package app.sentrek.record

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.zip.GZIPInputStream
import java.util.zip.InflaterInputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The vector tiles of the downloaded areas, read straight from MapLibre's offline database:
 * what the map knows, with no network and no map on screen. Uncompressed, ready for
 * [app.sentrek.geo.overlay.VectorTile]. Nothing when the database is busy or not there.
 */
@Singleton
class OfflineTiles @Inject constructor(@ApplicationContext private val context: Context) {
    /** One tile at [z]/[x]/[y]; null when it isn't downloaded. */
    fun tile(z: Int, x: Int, y: Int): ByteArray? = read { db, template ->
        db.rawQuery(
            "SELECT data, compressed FROM tiles WHERE url_template = ? AND z = ? AND x = ? AND y = ? LIMIT 1",
            arrayOf(template, "$z", "$x", "$y"),
        ).use { c -> if (c.moveToFirst()) unpack(c.getBlob(0), c.getInt(1) != 0) else null }
    }

    /**
     * How many tiles there are at [z]: changes when areas are downloaded or deleted (the file's
     * date changes with every tile the map caches), for what is worked out from them to be read again.
     */
    fun count(z: Int): Long = read { db, template ->
        db.rawQuery("SELECT COUNT(*) FROM tiles WHERE url_template = ? AND z = ?", arrayOf(template, "$z"))
            .use { c -> if (c.moveToFirst()) c.getLong(0) else 0L }
    } ?: 0L

    /** Every downloaded tile at [z], with its x and y, one at a time. */
    fun forEach(z: Int, action: (x: Int, y: Int, bytes: ByteArray) -> Unit) {
        read { db, template ->
            db.rawQuery(
                "SELECT x, y, data, compressed FROM tiles WHERE url_template = ? AND z = ?",
                arrayOf(template, "$z"),
            ).use { c ->
                while (c.moveToNext()) {
                    val bytes = unpack(c.getBlob(2), c.getInt(3) != 0) ?: continue
                    action(c.getInt(0), c.getInt(1), bytes)
                }
            }
        }
    }

    private fun <T> read(block: (SQLiteDatabase, String) -> T?): T? {
        val file = File(context.filesDir, OFFLINE_DB)
        if (!file.exists()) return null
        return try {
            SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                // The address carries the map's version (it changes with each update): the newest.
                val template = db.rawQuery(
                    "SELECT url_template FROM tiles WHERE url_template LIKE ? ORDER BY url_template DESC LIMIT 1",
                    arrayOf("%{z}/{x}/{y}.pbf"),
                ).use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: return null
                block(db, template)
            }
        } catch (e: Exception) {
            // Busy (MapLibre writing) or a schema we don't know: nothing known, this time.
            Log.d(TAG, "Offline tiles unreadable: ${e.message}")
            null
        }
    }

    private fun unpack(data: ByteArray?, compressed: Boolean): ByteArray? {
        data ?: return null
        val raw = if (compressed) InflaterInputStream(data.inputStream()).use { it.readBytes() } else data
        // Tile servers often send them gzipped, and MapLibre keeps them so.
        return if (raw.size > 2 && raw[0] == 0x1f.toByte() && raw[1] == 0x8b.toByte()) {
            GZIPInputStream(raw.inputStream()).use { it.readBytes() }
        } else {
            raw
        }
    }

    private companion object {
        const val TAG = "OfflineTiles"
        const val OFFLINE_DB = "mbgl-offline.db"
    }
}
