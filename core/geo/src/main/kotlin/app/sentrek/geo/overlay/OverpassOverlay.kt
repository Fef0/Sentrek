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
 *   bookkeeping ([isBookkeeping]), shown when the user long-presses it.
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
            "out geom;"

    /** Streams [input] (Overpass JSON) to [output] (GeoJSON FeatureCollection). Returns the feature count. */
    fun convert(input: Reader, output: Writer, toleranceM: Double = SIMPLIFY_TOLERANCE_M): Int {
        var count = 0
        output.write("""{"type":"FeatureCollection","features":[""")
        JsonReader(input).use { json ->
            json.beginObject()
            while (json.hasNext()) {
                if (json.nextName() != "elements") { json.skipValue(); continue }
                json.beginArray()
                while (json.hasNext()) {
                    val element = readElement(json)
                    val feature = when (element.type) {
                        "trail" -> trailFeature(element, toleranceM)
                        "poi" -> poiFeature(element)
                        else -> null
                    } ?: continue
                    if (count > 0) output.write(",")
                    output.write(feature)
                    count++
                }
                json.endArray()
            }
            json.endObject()
        }
        output.write("]}")
        output.flush()
        return count
    }

    private class Element(
        val type: String?,
        val id: Long?,
        val tags: Map<String, String>,
        val geometryType: String?,
        val lons: DoubleArray,
        val lats: DoubleArray,
    )

    private fun readElement(json: JsonReader): Element {
        var type: String? = null
        var id: Long? = null
        val tags = HashMap<String, String>()
        var geometryType: String? = null
        var lons = DoubleArray(0)
        var lats = DoubleArray(0)
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
        return Element(type, id, tags, geometryType, lons, lats)
    }

    /** Reads a Point (`[x, y]`) or LineString (`[[x, y], …]`) coordinate array. */
    private fun readCoordinates(json: JsonReader): Pair<DoubleArray, DoubleArray> {
        val lons = ArrayList<Double>()
        val lats = ArrayList<Double>()
        json.beginArray()
        if (json.peek() == JsonToken.NUMBER) {
            lons += json.nextDouble()
            lats += json.nextDouble()
            while (json.hasNext()) json.skipValue()
        } else {
            while (json.hasNext()) {
                if (json.peek() != JsonToken.BEGIN_ARRAY) { json.skipValue(); continue }
                json.beginArray()
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
    private fun round5(v: Double): String = ((v * 1e5).roundToLong() / 1e5).toString()
}
