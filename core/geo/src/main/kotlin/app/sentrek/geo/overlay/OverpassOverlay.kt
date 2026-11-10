package app.sentrek.geo.overlay

import app.sentrek.geo.BoundingBox
import app.sentrek.geo.Simplify
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter
import java.io.Reader
import java.io.StringWriter
import java.io.Writer
import kotlin.math.roundToLong

/**
 * The per-cell hiking overlay: details the OpenMapTiles base tiles leave out. One Overpass
 * request returns both, converted into one compact GeoJSON file:
 * - trail lines (every footpath and track): `s` = SAC grade 1–6 (absent when ungraded),
 *   `v` = visibility 1–6 (0 when unknown), `t` = 1 on tracks, `id` = "way/…";
 * - hiking points (huts, water, caves, guideposts, crosses, named peaks and passes…):
 *   `k` = [PoiKind.icon], `n` = name,
 *   `id` = "node/…" (or way/relation), plus every other OSM tag of the point except mapper
 *   bookkeeping ([isBookkeeping]), shown when the user long-presses it;
 * - waymarked hiking routes (international to local, e.g. CAI 708 or Sentiero Italia): one
 *   line per route and way, roads included so a route never breaks, with
 *   `route_1_network` = iwn/nwn/rwn/lwn (named as in the base tiles, so the base style's
 *   colours apply), `w` = 4 (international) to 1 (local), and `m` = the waymark as an
 *   `osmc:symbol` ([OsmcSymbol]; a badge with the number when the route has none).
 *   For the long-press sheet: `rid` = the relation id, `n` = its name, `p` = the routes it
 *   is part of (stage → region → Sentiero Italia), one per line, and the route's own
 *   [ROUTE_FACTS] tags (from, to, distance…) under their OSM keys.
 */
object OverpassOverlay {
    /** About 1 m: plenty for trails, keeps lines visually intact at z17. */
    const val SIMPLIFY_TOLERANCE_M = 2.0

    fun query(box: BoundingBox): String =
        "[out:json][timeout:180][bbox:${box.south},${box.west},${box.north},${box.east}];" +
            // Every path and track, graded or not, so they show when zoomed out; footways and
            // steps only when graded, which leaves out pavements.
            "(way[\"highway\"~\"^(path|bridleway|track)$\"];" +
            "way[\"highway\"~\"^(footway|steps)$\"][\"sac_scale\"];);" +
            "convert trail ::id=id(),hw=t[\"highway\"],sac=t[\"sac_scale\"],vis=t[\"trail_visibility\"],::geom=geom();" +
            "out geom;" +
            "(" +
            "nwr[\"natural\"~\"^(cave_entrance|spring)$\"];" +
            "nwr[\"tourism\"~\"^(wilderness_hut|alpine_hut|viewpoint|camp_site)$\"];" +
            "nwr[\"amenity\"=\"shelter\"][\"shelter_type\"~\"^(${PoiKind.MOUNTAIN_SHELTERS.joinToString("|")})$\"];" +
            "nwr[\"amenity\"~\"^(water_point|drinking_water)$\"];" +
            "nwr[\"historic\"~\"^(memorial|wayside_cross|wayside_shrine|archaeological_site|castle|ruins|monument)$\"];" +
            "nwr[\"man_made\"=\"cross\"];" +
            "node[\"information\"~\"^(guidepost|board|map)$\"];" +
            "node[\"waterway\"=\"waterfall\"];" +
            "node[\"emergency\"=\"phone\"];" +
            // Named only: an unnamed bump means nothing in a list of places ahead.
            "node[\"natural\"~\"^(peak|saddle)$\"][\"name\"];" +
            "node[\"mountain_pass\"=\"yes\"][\"name\"];" +
            ");" +
            "convert poi ::=::,::id=id(),$OSM_TYPE=type(),::geom=center(geom());" +
            "out geom;" +
            // Routes and the routes they're part of (their members by id), then the ways,
            // only within the cell.
            "rel[\"route\"~\"^(hiking|foot)$\"]->.routes;" +
            "rel(br.routes)->.up1;rel(br.up1)->.up2;" +
            "(.routes;.up1;.up2;);out body;" +
            "way(r.routes)(${box.south},${box.west},${box.north},${box.east});" +
            "convert route ::id=id(),::geom=geom();" +
            "out geom;"

    /** Streams [input] (Overpass JSON) to [output] (GeoJSON FeatureCollection). Returns the feature count. */
    fun convert(input: Reader, output: Writer, toleranceM: Double = SIMPLIFY_TOLERANCE_M): Int {
        var count = 0
        val relations = ArrayList<Element>()
        var routes: Map<Long, List<Route>>? = null
        output.write("""{"type":"FeatureCollection","features":[""")
        JsonReader(input).use { json ->
            json.beginObject()
            while (json.hasNext()) {
                if (json.nextName() != "elements") { json.skipValue(); continue }
                json.beginArray()
                while (json.hasNext()) {
                    val element = readElement(json)
                    val features = when (element.type) {
                        "trail" -> listOfNotNull(trailFeature(element, toleranceM))
                        "poi" -> listOfNotNull(poiFeature(element))
                        "relation" -> {
                            relations += element
                            emptyList()
                        }
                        "route" -> {
                            val byWay = routes ?: routesByWay(relations).also { routes = it }
                            routeFeatures(element, element.id?.let(byWay::get).orEmpty(), toleranceM)
                        }
                        else -> emptyList()
                    }
                    for (feature in features) {
                        if (count > 0) output.write(",")
                        output.write(feature)
                        count++
                    }
                }
                json.endArray()
            }
            json.endObject()
        }
        output.write("]}")
        output.flush()
        return count
    }

    internal class Element(
        val type: String?,
        val id: Long?,
        val tags: Map<String, String>,
        val geometryType: String?,
        val lons: DoubleArray,
        val lats: DoubleArray,
        val ways: List<Long>,
        val relations: List<Long>,
    )

    private data class Route(
        val id: Long,
        val network: String,
        val mark: String?,
        val name: String?,
        val partOf: List<String>,
        val facts: Map<String, String>,
    )

    internal fun readElement(json: JsonReader): Element {
        var type: String? = null
        var id: Long? = null
        val tags = HashMap<String, String>()
        var geometryType: String? = null
        var lons = DoubleArray(0)
        var lats = DoubleArray(0)
        val ways = ArrayList<Long>()
        val relations = ArrayList<Long>()
        json.beginObject()
        while (json.hasNext()) {
            when (json.nextName()) {
                "type" -> type = json.nextString()
                "id" -> id = json.nextLong()
                "tags" -> {
                    json.beginObject()
                    while (json.hasNext()) {
                        val key = json.nextName()
                        if (json.peek() == JsonToken.STRING) tags[key] = json.nextString() else json.skipValue()
                    }
                    json.endObject()
                }
                "members" -> {
                    json.beginArray()
                    while (json.hasNext()) {
                        val (memberType, ref) = readMember(json) ?: continue
                        if (memberType == "way") ways += ref else if (memberType == "relation") relations += ref
                    }
                    json.endArray()
                }
                "geometry" -> {
                    json.beginObject()
                    while (json.hasNext()) {
                        when (json.nextName()) {
                            "type" -> geometryType = json.nextString()
                            "coordinates" -> readCoordinates(json).let { (x, y) -> lons = x; lats = y }
                            else -> json.skipValue()
                        }
                    }
                    json.endObject()
                }
                else -> json.skipValue()
            }
        }
        json.endObject()
        return Element(type, id, tags, geometryType, lons, lats, ways, relations)
    }

    /** A relation member's type and id. */
    private fun readMember(json: JsonReader): Pair<String, Long>? {
        var type: String? = null
        var ref: Long? = null
        json.beginObject()
        while (json.hasNext()) {
            when (json.nextName()) {
                "type" -> type = json.nextString()
                "ref" -> ref = json.nextLong()
                else -> json.skipValue()
            }
        }
        json.endObject()
        return if (type != null && ref != null) type to ref else null
    }

    /** Reads a Point (`[x, y]`), LineString (`[[x, y], …]`) or Polygon's outer ring. [nested]: the array is already open. */
    private fun readCoordinates(json: JsonReader, nested: Boolean = false): Pair<DoubleArray, DoubleArray> {
        val lons = ArrayList<Double>()
        val lats = ArrayList<Double>()
        if (nested) {
            // Inside a Polygon: this is the ring, a list of [x, y].
            while (json.hasNext()) {
                json.beginArray()
                lons += json.nextDouble()
                lats += json.nextDouble()
                while (json.hasNext()) json.skipValue()
                json.endArray()
            }
            return lons.toDoubleArray() to lats.toDoubleArray()
        }
        json.beginArray()
        if (json.peek() == JsonToken.NUMBER) {
            lons += json.nextDouble()
            lats += json.nextDouble()
            while (json.hasNext()) json.skipValue()
        } else {
            while (json.hasNext()) {
                if (json.peek() != JsonToken.BEGIN_ARRAY) { json.skipValue(); continue }
                json.beginArray()
                // A Polygon (a closed way mapped as an area): its outer ring, as a line.
                if (json.peek() == JsonToken.BEGIN_ARRAY) {
                    if (lons.isEmpty()) readCoordinates(json, nested = true).let { (x, y) -> lons += x.toList(); lats += y.toList() }
                    while (json.hasNext()) json.skipValue()
                    json.endArray()
                    continue
                }
                lons += json.nextDouble()
                lats += json.nextDouble()
                while (json.hasNext()) json.skipValue()
                json.endArray()
            }
        }
        json.endArray()
        return lons.toDoubleArray() to lats.toDoubleArray()
    }

    private fun trailFeature(e: Element, toleranceM: Double): String? {
        val sac = SacScale.fromOsm(e.tags["sac"])
        if (e.geometryType != "LineString" || e.lons.size < 2) return null
        val vis = TrailVisibility.fromOsm(e.tags["vis"])?.level ?: 0
        val coords = Simplify.douglasPeucker(e.lons, e.lats, toleranceM)
            .joinToString(",") { i -> "[${round5(e.lons[i])},${round5(e.lats[i])}]" }
        val id = e.id?.let { ""","id":"way/$it"""" }.orEmpty()
        val grade = sac?.let { """"s":${it.grade},""" }.orEmpty()
        val track = if (e.tags["hw"] == "track") ""","t":1""" else ""
        return """{"type":"Feature","properties":{$grade"v":$vis$track$id},""" +
            """"geometry":{"type":"LineString","coordinates":[$coords]}}"""
    }

    /** Way id → the routes running on it, each with the routes it's part of. */
    private fun routesByWay(relations: List<Element>): Map<Long, List<Route>> {
        val byId = relations.filter { it.id != null }.associateBy { it.id!! }
        val parents = HashMap<Long, MutableList<Element>>()
        for (r in relations) for (child in r.relations) parents.getOrPut(child) { ArrayList(1) } += r
        val result = HashMap<Long, MutableList<Route>>()
        for (r in relations) {
            if (r.ways.isEmpty()) continue
            val route = route(r, partOf(r, parents, byId)) ?: continue
            for (way in r.ways) result.getOrPut(way) { ArrayList(1) } += route
        }
        return result
    }

    /** Names of the routes [r] belongs to, nearest first, without repeats. */
    private fun partOf(r: Element, parents: Map<Long, List<Element>>, byId: Map<Long, Element>): List<String> {
        val names = LinkedHashSet<String>()
        var level = listOfNotNull(r.id).flatMap { parents[it].orEmpty() }
        repeat(PARENT_LEVELS) {
            level.mapNotNullTo(names) { displayName(it.tags) }
            level = level.flatMap { p -> p.id?.let { parents[it] }.orEmpty() }.filter { it.id in byId }
        }
        displayName(r.tags)?.let(names::remove)
        return names.toList()
    }

    /** A hiking route with something to show: a known network, a waymark or a number. */
    private fun route(r: Element, partOf: List<String>): Route? {
        val tags = r.tags
        val osmc = tags["osmc:symbol"]?.trim()?.takeIf { OsmcSymbol.parse(it) != null }
        val ref = tags["ref"]?.trim()?.takeIf { it.isNotEmpty() && it.length <= MAX_REF }
        val network = tags["network"]?.takeIf { it in NETWORKS } ?: if (osmc != null || ref != null) "lwn" else return null
        return Route(
            id = r.id ?: return null,
            network = network,
            mark = osmc ?: ref?.let { OsmcSymbol.fallback(network, it) },
            name = displayName(tags),
            partOf = partOf,
            facts = ROUTE_FACTS.mapNotNull { key -> tags[key]?.takeIf { it.isNotBlank() }?.let { key to it } }.toMap(),
        )
    }

    /** Many stages have no name, only a description ("Sentiero Italia - Tappa V23B"). */
    internal fun displayName(tags: Map<String, String>): String? =
        (tags["name"] ?: tags["description"]?.takeIf { it.length <= MAX_NAME })?.trim()?.takeIf { it.isNotEmpty() }

    private fun routeFeatures(e: Element, routes: List<Route>, toleranceM: Double): List<String> {
        if (routes.isEmpty() || e.geometryType != "LineString" || e.lons.size < 2) return emptyList()
        val coords = Simplify.douglasPeucker(e.lons, e.lats, toleranceM)
            .joinToString(",") { i -> "[${round5(e.lons[i])},${round5(e.lats[i])}]" }
        return routes.distinct().map { r ->
            val properties = StringWriter()
            JsonWriter(properties).use { json ->
                json.beginObject()
                json.name(NETWORK).value(r.network)
                json.name("w").value(NETWORKS.size - NETWORKS.indexOf(r.network))
                r.mark?.let { json.name("m").value(it) }
                json.name("rid").value(r.id)
                r.name?.let { json.name("n").value(it) }
                if (r.partOf.isNotEmpty()) json.name("p").value(r.partOf.joinToString("\n"))
                for ((key, value) in r.facts) json.name(key).value(value)
                json.endObject()
            }
            """{"type":"Feature","properties":$properties,"geometry":{"type":"LineString","coordinates":[$coords]}}"""
        }
    }

    private fun poiFeature(e: Element): String? {
        val kind = PoiKind.classify(e.tags) ?: return null
        if (e.geometryType != "Point" || e.lons.isEmpty()) return null
        val properties = StringWriter()
        JsonWriter(properties).use { json ->
            json.beginObject()
            json.name("k").value(kind.icon)
            e.tags["name"]?.takeIf { it.isNotBlank() }?.let { json.name("n").value(it) }
            val osmType = e.tags[OSM_TYPE]
            if (osmType != null && e.id != null) json.name("id").value("$osmType/${e.id}")
            for ((key, value) in e.tags.toSortedMap()) {
                if (key in RESERVED || value.isBlank() || isBookkeeping(key)) continue
                json.name(key).value(value)
            }
            json.endObject()
        }
        return """{"type":"Feature","properties":$properties,""" +
            """"geometry":{"type":"Point","coordinates":[${round5(e.lons[0])},${round5(e.lats[0])}]}}"""
    }

    /** Route lines carry their network under the base tiles' name for it. */
    const val NETWORK = "route_1_network"

    /** Most important first. */
    private val NETWORKS = listOf("iwn", "nwn", "rwn", "lwn")

    /** Longer "refs" are descriptions, not numbers painted on a rock. */
    private const val MAX_REF = 5

    /** Longer descriptions are prose, not a name. */
    private const val MAX_NAME = 80

    /** Stage → region → whole route, as Sentiero Italia and E1 are mapped. */
    private const val PARENT_LEVELS = 2

    /** Route tags worth showing on the trail, kept as in OSM. */
    val ROUTE_FACTS = listOf(
        "ref", "from", "to", "distance", "ascent", "descent", "duration:forward", "duration:backward",
        "cai_scale", "operator", "website",
    )

    /** Overpass-side helper tag carrying the element type (node/way/relation). */
    private const val OSM_TYPE = "osm_type"

    /** Our own property names, plus `name` (stored as `n`). */
    private val RESERVED = setOf("k", "n", "id", "name", OSM_TYPE)

    /** Tags about the mapping itself, of no use on the trail. */
    private fun isBookkeeping(key: String): Boolean {
        val k = key.lowercase()
        return k.startsWith("source") || k.startsWith("check_date") || k.startsWith("fixme") ||
            k.startsWith("survey") || k == "created_by"
    }

    /** Five decimals ≈ 1 m. */
    internal fun round5(v: Double): String = ((v * 1e5).roundToLong() / 1e5).toString()
}
