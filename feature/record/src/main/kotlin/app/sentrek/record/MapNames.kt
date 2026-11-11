package app.sentrek.record

import android.util.Log
import app.sentrek.geo.LatLon
import app.sentrek.geo.overlay.VectorTile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.sinh

/**
 * Something named on the map: [kind] as OpenMapTiles has it (village, peak, lake, restaurant,
 * parking, chapel…), [group] what it is broadly, for its icon.
 */
data class MapName(val name: String, val kind: String, val group: Group, val position: LatLon) {
    enum class Group { Settlement, Mountain, Water, Point }
}

/**
 * Everything with a name in the downloaded areas' own map tiles: towns and villages, peaks,
 * lakes, and the named points (huts, chapels, restaurants, car parks…). What a search for
 * "Nicolosi", "Etna" or a car park should find, offline. Read once and kept until the map
 * downloads change.
 */
@Singleton
class MapNames @Inject constructor(private val tiles: OfflineTiles) {
    private val lock = Mutex()
    private var cached: List<MapName>? = null
    private var cachedStamp = 0L

    suspend fun all(): List<MapName> = withContext(Dispatchers.IO) {
        lock.withLock {
            val stamp = tiles.count(OVERVIEW_ZOOM)
            cached?.takeIf { stamp == cachedStamp }?.let { return@withLock it }
            read().also { cached = it; cachedStamp = stamp }
        }
    }

    private fun read(): List<MapName> {
        val names = ArrayList<MapName>()
        runCatching {
            // Towns, mountains and lakes are all there at a zoom where an area is a few tiles.
            tiles.forEach(OVERVIEW_ZOOM) { x, y, bytes ->
                fun add(layer: String, group: MapName.Group, keep: (Map<String, String>) -> Boolean) =
                    VectorTile.points(bytes, layer) { keep(it) && !it["name"].isNullOrBlank() }.mapTo(names) { p ->
                        val ele = p.tags["ele"]?.toDoubleOrNull()
                        val at = position(OVERVIEW_ZOOM, x, y, p)
                        MapName(p.tags.getValue("name"), p.tags["class"] ?: "", group, LatLon(at.lat, at.lon, ele))
                    }
                add(PLACES, MapName.Group.Settlement) { it["class"] in SETTLEMENTS }
                add(PEAKS, MapName.Group.Mountain) { true }
                add(WATER_NAMES, MapName.Group.Water) { true }
            }
            // The other named points only in the most detailed tiles.
            tiles.forEach(DETAIL_ZOOM) { x, y, bytes ->
                VectorTile.points(bytes, POIS) { !it["name"].isNullOrBlank() }.mapTo(names) { p ->
                    MapName(p.tags.getValue("name"), p.tags["subclass"] ?: p.tags["class"] ?: "", MapName.Group.Point, position(DETAIL_ZOOM, x, y, p))
                }
            }
        }.onFailure { Log.w(TAG, "Cannot read the map's names", it) }
        // A place near a tile edge is in both tiles.
        return names.distinctBy { Triple(it.name, (it.position.lat * 200).toLong(), (it.position.lon * 200).toLong()) }
    }

    private fun position(zoom: Int, x: Int, y: Int, p: VectorTile.Point): LatLon {
        val n = (1 shl zoom).toDouble()
        val fx = (x + p.x.toDouble() / p.extent) / n
        val fy = (y + p.y.toDouble() / p.extent) / n
        val lat = Math.toDegrees(atan(sinh(PI * (1 - 2 * fy))))
        return LatLon(lat.coerceIn(-90.0, 90.0), (fx * 360 - 180).coerceIn(-180.0, 180.0))
    }

    private companion object {
        const val TAG = "MapNames"
        const val OVERVIEW_ZOOM = 11
        const val DETAIL_ZOOM = 14
        const val PLACES = "place"
        const val PEAKS = "mountain_peak"
        const val WATER_NAMES = "water_name"
        const val POIS = "poi"
        val SETTLEMENTS = setOf("city", "town", "village", "hamlet", "suburb", "isolated_dwelling", "locality")
    }
}
