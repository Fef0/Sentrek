package app.sentrek.record

import android.util.Log
import app.sentrek.geo.BoundingBox
import app.sentrek.geo.LatLon
import app.sentrek.geo.overlay.NamedLine
import app.sentrek.geo.overlay.NamedLines
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
    private var cached: Pair<List<MapName>, List<NamedLine>>? = null
    private var cachedStamp = 0L

    suspend fun all(): List<MapName> = both().first

    /** The paths and tracks the map names ("Sentiero del Monte…"), each in one piece. */
    suspend fun paths(): List<NamedLine> = both().second

    private suspend fun both(): Pair<List<MapName>, List<NamedLine>> = withContext(Dispatchers.IO) {
        lock.withLock {
            val stamp = tiles.count(OVERVIEW_ZOOM)
            cached?.takeIf { stamp == cachedStamp }?.let { return@withLock it }
            read().also { cached = it; cachedStamp = stamp }
        }
    }

    private fun read(): Pair<List<MapName>, List<NamedLine>> {
        val names = ArrayList<MapName>()
        val paths = ArrayList<NamedLine>()
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
            // The other named points, and the paths' names, only in the most detailed tiles.
            tiles.forEach(DETAIL_ZOOM) { x, y, bytes ->
                VectorTile.points(bytes, POIS) { !it["name"].isNullOrBlank() }.mapTo(names) { p ->
                    MapName(p.tags.getValue("name"), p.tags["subclass"] ?: p.tags["class"] ?: "", MapName.Group.Point, position(DETAIL_ZOOM, x, y, p))
                }
                VectorTile.lines(bytes, PATH_NAMES) { it["class"] in PATH_CLASSES && !it["name"].isNullOrBlank() }.mapNotNullTo(paths) { line ->
                    box(DETAIL_ZOOM, x, y, line)?.let { NamedLine(NamedLines.clean(line.tags.getValue("name")), NamedLine.Kind.Path, it) }
                }
            }
        }.onFailure { Log.w(TAG, "Cannot read the map's names", it) }
        // A place near a tile edge is in both tiles; a path is cut at each edge.
        return names.distinctBy { Triple(it.name, (it.position.lat * 200).toLong(), (it.position.lon * 200).toLong()) } to
            NamedLines.merge(paths)
    }

    private fun box(zoom: Int, x: Int, y: Int, line: VectorTile.Line): BoundingBox? {
        var south = 90.0
        var north = -90.0
        var west = 180.0
        var east = -180.0
        for (part in line.parts) {
            for (i in 0 until part.size / 2) {
                val p = position(zoom, x, y, part[2 * i], part[2 * i + 1], line.extent)
                south = minOf(south, p.lat); north = maxOf(north, p.lat)
                west = minOf(west, p.lon); east = maxOf(east, p.lon)
            }
        }
        return if (south <= north) BoundingBox(south, west, north, east) else null
    }

    private fun position(zoom: Int, x: Int, y: Int, p: VectorTile.Point): LatLon = position(zoom, x, y, p.x, p.y, p.extent)

    private fun position(zoom: Int, x: Int, y: Int, px: Int, py: Int, extent: Int): LatLon {
        val n = (1 shl zoom).toDouble()
        val fx = (x + px.toDouble() / extent) / n
        val fy = (y + py.toDouble() / extent) / n
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
        const val PATH_NAMES = "transportation_name"
        val PATH_CLASSES = setOf("path", "track")
        val SETTLEMENTS = setOf("city", "town", "village", "hamlet", "suburb", "isolated_dwelling", "locality")
    }
}
