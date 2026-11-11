package app.sentrek.geo.overlay

import app.sentrek.geo.BoundingBox
import app.sentrek.geo.Simplify
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonWriter
import java.io.Reader
import java.io.StringWriter
import java.io.Writer

/**
 * The per-cell details of the [MapKind.extras], one GeoJSON file each, as for the hiking
 * overlay ([OverpassOverlay]):
 * - [MapKind.Winter], pistes: one line per way and kind of piste, from the way's own tags or
 *   the piste routes it is part of (cross-country loops are often mapped that way).
 *   `pt` = piste:type (downhill, nordic, skitour, sled, hike…), `pd` = piste:difficulty,
 *   `g` = piste:grooming, `n` = name or number, `id` = "way/…". Pistes mapped as areas
 *   are Polygons, with `a` = 1.
 * - [MapKind.Cycling], waymarked cycle and mountain bike routes: one line per route and way,
 *   `cn` = icn/ncn/rcn/lcn, or mtb for mountain bike routes, `w` = 4 (international) to 1
 *   (local, and mtb), `ref` and `n` (name), `rid` = the relation id, and the route's [CYCLE_FACTS]
 *   under their OSM keys: the two directions of a stage often share a name and number.
 */
object OverpassExtras {
    fun query(kind: MapKind, box: BoundingBox): String {
        val bbox = "${box.south},${box.west},${box.north},${box.east}"
        val head = "[out:json][timeout:180][bbox:$bbox];"
        return when (kind) {
            MapKind.Winter -> head +
                "rel[\"route\"=\"piste\"]->.r;.r out body;" +
                "(way[\"piste:type\"];way(r.r)($bbox););" +
                "convert piste ::id=id(),pt=t[\"piste:type\"],pd=t[\"piste:difficulty\"],pg=t[\"piste:grooming\"]," +
                "pn=t[\"piste:name\"],pr=t[\"piste:ref\"],nm=t[\"name\"],rf=t[\"ref\"],ar=t[\"area\"],::geom=geom();" +
                "out geom;"
            MapKind.Cycling -> head +
                "rel[\"route\"~\"^(bicycle|mtb)$\"]->.r;.r out body;" +
                "way(r.r)($bbox);" +
                "convert bike ::id=id(),::geom=geom();" +
                "out geom;"
            MapKind.Hiking -> OverpassOverlay.query(box)
        }
    }

    /** Streams [input] (Overpass JSON) to [output] (GeoJSON FeatureCollection). Returns the feature count. */
    fun convert(kind: MapKind, input: Reader, output: Writer, toleranceM: Double = OverpassOverlay.SIMPLIFY_TOLERANCE_M): Int {
        require(kind != MapKind.Hiking) { "Hiking details come from OverpassOverlay" }
        var count = 0
        // Relations come first in the answer ("out body" before the ways).
        val relations = ArrayList<OverpassOverlay.Element>()
        var byWay: Map<Long, List<OverpassOverlay.Element>>? = null
        output.write("""{"type":"FeatureCollection","features":[""")
        JsonReader(input).use { json ->
            json.beginObject()
            while (json.hasNext()) {
                if (json.nextName() != "elements") { json.skipValue(); continue }
                json.beginArray()
                while (json.hasNext()) {
                    val element = OverpassOverlay.readElement(json)
                    if (element.type == "relation") {
                        relations += element
                        continue
                    }
                    val parents = (byWay ?: waysOf(relations).also { byWay = it })[element.id].orEmpty()
                    val features = when (element.type) {
                        "piste" -> pisteFeatures(element, parents, toleranceM)
                        "bike" -> bikeFeatures(element, parents, toleranceM)
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

    private fun waysOf(relations: List<OverpassOverlay.Element>): Map<Long, List<OverpassOverlay.Element>> {
        val result = HashMap<Long, MutableList<OverpassOverlay.Element>>()
        for (r in relations) for (way in r.ways) result.getOrPut(way) { ArrayList(1) } += r
        return result
    }

    private fun pisteFeatures(e: OverpassOverlay.Element, routes: List<OverpassOverlay.Element>, toleranceM: Double): List<String> {
        if (e.lons.size < 2) return emptyList()
        // Overpass "convert" writes a missing tag as an empty string.
        val tags = e.tags.filterValues { it.isNotBlank() }
        val own = tags["pt"]?.let { Piste(it, tags["pd"], tags["pg"], name(tags["pn"], tags["pr"], tags["nm"], tags["rf"])) }
        // A way's own piste first; then each other kind of piste a route adds on it (a downhill
        // piste can also be part of a ski tour). The way's name goes to all, the route's if none.
        val pistes = LinkedHashMap<String, Piste>()
        own?.let { pistes[it.type] = it }
        for (r in routes) {
            val type = r.tags["piste:type"]?.takeIf { it.isNotBlank() } ?: continue
            if (type in pistes) continue
            val routeName = name(r.tags["piste:name"], r.tags["piste:ref"], r.tags["name"], r.tags["ref"])
            pistes[type] = Piste(
                type, r.tags["piste:difficulty"]?.takeIf { it.isNotBlank() }, r.tags["piste:grooming"]?.takeIf { it.isNotBlank() },
                own?.name ?: routeName,
            )
        }
        if (pistes.isEmpty()) return emptyList()
        val area = e.geometryType == "Polygon" || (tags["ar"] == "yes" && closed(e))
        val geometry = geometry(e, area, toleranceM) ?: return emptyList()
        return pistes.values.map { p ->
            val properties = StringWriter()
            JsonWriter(properties).use { json ->
                json.beginObject()
                json.name("pt").value(p.type)
                p.difficulty?.let { json.name("pd").value(it) }
                p.grooming?.let { json.name("g").value(it) }
                p.name?.let { json.name("n").value(it) }
                if (area) json.name("a").value(1)
                e.id?.let { json.name("id").value("way/$it") }
                json.endObject()
            }
            """{"type":"Feature","properties":$properties,"geometry":$geometry}"""
        }
    }

    private class Piste(val type: String, val difficulty: String?, val grooming: String?, val name: String?)

    private fun bikeFeatures(e: OverpassOverlay.Element, routes: List<OverpassOverlay.Element>, toleranceM: Double): List<String> {
        if (routes.isEmpty() || e.lons.size < 2) return emptyList()
        val geometry = geometry(e, area = false, toleranceM) ?: return emptyList()
        return routes.distinctBy { it.id }.mapNotNull { r ->
            val network = if (r.tags["route"] == "mtb") MTB else r.tags["network"]?.takeIf { it in BIKE_NETWORKS } ?: "lcn"
            val ref = r.tags["ref"]?.trim()?.takeIf { it.isNotEmpty() && it.length <= MAX_REF }
            val name = OverpassOverlay.displayName(r.tags)
            if (ref == null && name == null && r.tags["network"] == null) return@mapNotNull null
            val properties = StringWriter()
            JsonWriter(properties).use { json ->
                json.beginObject()
                json.name(NETWORK).value(network)
                json.name("w").value(if (network == MTB) 1 else BIKE_NETWORKS.size - BIKE_NETWORKS.indexOf(network))
                ref?.let { json.name("ref").value(it) }
                name?.let { json.name("n").value(it) }
                r.id?.let { json.name("rid").value(it) }
                for (key in CYCLE_FACTS) {
                    if (key == "ref") continue
                    r.tags[key]?.takeIf { it.isNotBlank() }?.let { json.name(key).value(it) }
                }
                json.endObject()
            }
            """{"type":"Feature","properties":$properties,"geometry":$geometry}"""
        }
    }

    private fun closed(e: OverpassOverlay.Element) =
        e.lons.size >= 4 && e.lons.first() == e.lons.last() && e.lats.first() == e.lats.last()

    private fun geometry(e: OverpassOverlay.Element, area: Boolean, toleranceM: Double): String? {
        val kept = Simplify.douglasPeucker(e.lons, e.lats, toleranceM)
        if (kept.size < 2) return null
        val coords = kept.joinToString(",") { i -> "[${OverpassOverlay.round5(e.lons[i])},${OverpassOverlay.round5(e.lats[i])}]" }
        return if (area && kept.size >= 4) {
            """{"type":"Polygon","coordinates":[[$coords]]}"""
        } else {
            """{"type":"LineString","coordinates":[$coords]}"""
        }
    }

    /** A piste's name, else its number. */
    private fun name(vararg candidates: String?): String? =
        candidates.firstNotNullOfOrNull { it?.trim()?.takeIf { s -> s.isNotEmpty() && s.length <= MAX_NAME } }

    /** Cycle route lines carry their network here. */
    const val NETWORK = "cn"
    const val MTB = "mtb"

    /** Most important first. */
    private val BIKE_NETWORKS = listOf("icn", "ncn", "rcn", "lcn")

    /** Route tags worth showing on the card, kept as in OSM. */
    val CYCLE_FACTS: List<String> = OverpassOverlay.ROUTE_FACTS + listOf("stage", "cai_scale:mtb", "mtb:scale")

    private const val MAX_REF = 8
    private const val MAX_NAME = 80
}
