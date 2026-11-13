package app.sentrek.geo.overlay

import app.sentrek.geo.BoundingBox
import app.sentrek.geo.Geo
import app.sentrek.geo.LatLon
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.Reader

/**
 * A way with a name, for search: a via ferrata, a waymarked route, or a path the map names.
 * [box] holds all of it; [grade] is a via ferrata's (0–6, -1 when ungraded); [mark] a route's
 * waymark (osmc:symbol) and [ref] its number.
 */
data class NamedLine(
    val name: String,
    val kind: Kind,
    val box: BoundingBox,
    val ref: String? = null,
    val mark: String? = null,
    val grade: Int? = null,
    /** The route relation, when it is one: its pieces in several areas are one route. */
    val routeId: Long? = null,
) {
    enum class Kind { Ferrata, Route, Path }

    val middle: LatLon get() = LatLon((box.south + box.north) / 2, (box.west + box.east) / 2)

    /** What search matches: the name, and a route's number ("708" finds "CAI 708"). */
    val searchText: String get() = listOfNotNull(name, ref?.takeIf { !name.contains(it) }).joinToString(" ")
}

fun interface NamedLineSource {
    suspend fun namedLines(): List<NamedLine>
}

object NamedLines {
    /** Pieces of the same name this close are one way: a path split at every junction. */
    const val JOIN_M = 1_500.0

    /**
     * The via ferratas and waymarked routes of one overlay file ([OverpassOverlay]), each in
     * one piece. Streamed: the files are several megabytes, mostly unnamed trails.
     */
    fun read(input: Reader): List<NamedLine> {
        val out = ArrayList<NamedLine>()
        JsonReader(input).use { json ->
            json.beginObject()
            while (json.hasNext()) {
                if (json.nextName() != "features") { json.skipValue(); continue }
                json.beginArray()
                while (json.hasNext()) readFeature(json)?.let(out::add)
                json.endArray()
            }
            json.endObject()
        }
        return merge(out)
    }

    /**
     * Joins the pieces of each way: a route by its relation, anything else by kind and name
     * when the pieces touch or nearly ([JOIN_M]).
     */
    fun merge(lines: List<NamedLine>): List<NamedLine> {
        val result = ArrayList<NamedLine>()
        val byRoute = HashMap<Long, Int>()
        val byName = HashMap<String, MutableList<Int>>()
        for (line in lines) {
            val routeIndex = line.routeId?.let(byRoute::get)
            if (routeIndex != null) {
                result[routeIndex] = result[routeIndex].copy(box = union(result[routeIndex].box, line.box))
                continue
            }
            val key = line.kind.name + "|" + line.name.lowercase()
            if (line.routeId == null) {
                val near = byName[key]?.firstOrNull { close(result[it].box, line.box) }
                if (near != null) {
                    result[near] = result[near].copy(box = union(result[near].box, line.box), grade = result[near].grade ?: line.grade)
                    continue
                }
                byName.getOrPut(key) { ArrayList(1) } += result.size
            } else {
                byRoute[line.routeId] = result.size
            }
            result += line
        }
        return result
    }

    private fun readFeature(json: JsonReader): NamedLine? {
        var name: String? = null
        var ref: String? = null
        var mark: String? = null
        var grade: Int? = null
        var route: Long? = null
        var network = false
        var box: BoundingBox? = null
        json.beginObject()
        while (json.hasNext()) {
            when (json.nextName()) {
                "properties" -> {
                    json.beginObject()
                    while (json.hasNext()) {
                        val key = json.nextName()
                        if (json.peek() != JsonToken.STRING && json.peek() != JsonToken.NUMBER) { json.skipValue(); continue }
                        val value = json.nextString()
                        when (key) {
                            "n" -> name = value
                            "ref" -> ref = value
                            "m" -> mark = value
                            "f" -> grade = value.toDoubleOrNull()?.toInt()
                            "rid" -> route = value.toDoubleOrNull()?.toLong()
                            OverpassOverlay.NETWORK -> network = true
                        }
                    }
                    json.endObject()
                }
                "geometry" -> box = lineBox(json)
                else -> json.skipValue()
            }
        }
        json.endObject()
        box ?: return null
        val kind = when {
            grade != null -> NamedLine.Kind.Ferrata
            network -> NamedLine.Kind.Route
            else -> return null
        }
        val label = name?.let(::clean)?.takeIf { it.isNotEmpty() } ?: ref?.let(::clean)?.takeIf { it.isNotEmpty() } ?: return null
        return NamedLine(label, kind, box, ref = ref?.trim(), mark = mark, grade = grade, routeId = route)
    }

    /** A LineString's bounds; null for any other geometry (points, areas). */
    private fun lineBox(json: JsonReader): BoundingBox? {
        var type: String? = null
        var south = 90.0
        var north = -90.0
        var west = 180.0
        var east = -180.0
        var any = false
        json.beginObject()
        while (json.hasNext()) {
            when (json.nextName()) {
                "type" -> type = json.nextString()
                "coordinates" -> {
                    json.beginArray()
                    while (json.hasNext()) {
                        if (json.peek() != JsonToken.BEGIN_ARRAY) { json.skipValue(); continue }
                        json.beginArray()
                        if (json.peek() == JsonToken.NUMBER) {
                            val lon = json.nextDouble()
                            val lat = json.nextDouble()
                            south = minOf(south, lat); north = maxOf(north, lat)
                            west = minOf(west, lon); east = maxOf(east, lon)
                            any = true
                        }
                        while (json.hasNext()) json.skipValue()
                        json.endArray()
                    }
                    json.endArray()
                }
                else -> json.skipValue()
            }
        }
        json.endObject()
        return if (type == "LineString" && any) BoundingBox(south, west, north, east) else null
    }

    /** Names as mapped, with stray spaces ("Croda Rossa  - CAI 15B") made single. */
    fun clean(name: String): String = name.trim().replace(SPACES, " ")

    private val SPACES = Regex("\\s+")

    /**
     * One entry per way: a waymarked route named like a via ferrata it runs over is that via
     * ferrata, which says more (its grade).
     */
    fun withoutRepeats(lines: List<NamedLine>): List<NamedLine> {
        val ferratas = lines.filter { it.kind == NamedLine.Kind.Ferrata }
        return lines.filter { line ->
            line.kind != NamedLine.Kind.Route ||
                ferratas.none { it.name.equals(line.name, ignoreCase = true) && it.box.intersects(line.box) }
        }
    }

    fun union(a: BoundingBox, b: BoundingBox) =
        BoundingBox(minOf(a.south, b.south), minOf(a.west, b.west), maxOf(a.north, b.north), maxOf(a.east, b.east))

    /** Touching, or with corners within [JOIN_M]. */
    fun close(a: BoundingBox, b: BoundingBox): Boolean {
        if (a.intersects(b)) return true
        val lat = (a.south + a.north) / 2
        val dy = when {
            a.north < b.south -> Geo.distance(LatLon(a.north, 0.0), LatLon(b.south, 0.0))
            b.north < a.south -> Geo.distance(LatLon(b.north, 0.0), LatLon(a.south, 0.0))
            else -> 0.0
        }
        val dx = when {
            a.east < b.west -> Geo.distance(LatLon(lat, a.east), LatLon(lat, b.west))
            b.east < a.west -> Geo.distance(LatLon(lat, b.east), LatLon(lat, a.west))
            else -> 0.0
        }
        return dx * dx + dy * dy <= JOIN_M * JOIN_M
    }
}
