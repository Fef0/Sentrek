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
    /** One tile at [z]/[x]/[y]; null when it isn't downloaded. The newest map version that has it. */
    fun tile(z: Int, x: Int, y: Int): ByteArray? = read { db, templates ->
        templates.firstNotNullOfOrNull { template ->
            db.rawQuery(
                "SELECT data, compressed FROM tiles WHERE url_template = ? AND z = ? AND x = ? AND y = ? LIMIT 1",
                arrayOf(template, "$z", "$x", "$y"),
            ).use { c -> if (c.moveToFirst()) unpack(c.getBlob(0), c.getInt(1) != 0) else null }
        }
    }

    /**
     * How many tiles there are at [z]: changes when areas are downloaded or deleted (the file's
     * date changes with every tile the map caches), for what is worked out from them to be read again.
     */
    fun count(z: Int): Long = read { db, templates ->
        templates.sumOf { template ->
            db.rawQuery("SELECT COUNT(*) FROM tiles WHERE url_template = ? AND z = ?", arrayOf(template, "$z"))
                .use { c -> if (c.moveToFirst()) c.getLong(0) else 0L }
        }
    } ?: 0L

    /** Every downloaded tile at [z], with its x and y, one at a time; each place once, from the newest version. */
    fun forEach(z: Int, action: (x: Int, y: Int, bytes: ByteArray) -> Unit) {
        read { db, templates ->
            val done = HashSet<Long>()
            for (template in templates) {
                db.rawQuery(
                    "SELECT x, y, data, compressed FROM tiles WHERE url_template = ? AND z = ?",
                    arrayOf(template, "$z"),
                ).use { c ->
                    while (c.moveToNext()) {
                        val x = c.getInt(0)
                        val y = c.getInt(1)
                        if (!done.add(x.toLong() shl 32 or (y.toLong() and 0xFFFFFFFFL))) continue
                        val bytes = unpack(c.getBlob(2), c.getInt(3) != 0) ?: continue
                        action(x, y, bytes)
                    }
                }
            }
        }
    }

    /**
     * [block] with the database and the map's tile addresses, newest first. The address carries
     * the map's version, and each area keeps the version it was downloaded or last updated at:
     * areas downloaded at different times are under different addresses.
     */
    private fun <T> read(block: (SQLiteDatabase, List<String>) -> T?): T? {
        val file = File(context.filesDir, OFFLINE_DB)
        if (!file.exists()) return null
        return try {
            SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                val templates = templates(db).filter { it.endsWith(VECTOR_TILE) }.sortedDescending()
                if (templates.isEmpty()) null else block(db, templates)
            }
        } catch (e: Exception) {
            // Busy (MapLibre writing) or a schema we don't know: nothing known, this time.
            Log.d(TAG, "Offline tiles unreadable: ${e.message}")
            null
        }
    }

    /**
     * Every tile address in the database, one look-up each through the table's index (it starts
     * with the address): a handful, where reading them off every tile would go through them all.
     */
    private fun templates(db: SQLiteDatabase): List<String> {
        val found = mutableListOf<String>()
        var after = ""
        while (found.size < MAX_TEMPLATES) {
            val next = db.rawQuery("SELECT MIN(url_template) FROM tiles WHERE url_template > ?", arrayOf(after))
                .use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null } ?: break
            found += next
            after = next
        }
        return found
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
        const val VECTOR_TILE = "{z}/{x}/{y}.pbf"
        const val MAX_TEMPLATES = 50
    }
}
