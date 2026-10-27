package app.sentrek.geo.trails

import app.sentrek.geo.BoundingBox
import app.sentrek.geo.Simplify
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.Reader
import java.io.Writer
import kotlin.math.roundToLong

/**
 * Builds the Overpass query for trail details in [box], and converts its answer into the
 * compact GeoJSON stored per region cell. Feature properties: `s` = SAC grade 1–6,
 * `v` = visibility 1–6 (0 when unknown).
 */
object OverpassTrails {
    /** About 1 m: plenty for trails, keeps lines visually intact at z17. */
    const val SIMPLIFY_TOLERANCE_M = 2.0

    fun query(box: BoundingBox): String =
        "[out:json][timeout:120][bbox:${box.south},${box.west},${box.north},${box.east}];" +
            "way[\"highway\"~\"^(path|footway|track|steps|bridleway)$\"][\"sac_scale\"];" +
            "convert way ::id=id(),sac=t[\"sac_scale\"],vis=t[\"trail_visibility\"],::geom=geom();" +
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
                    val trail = readElement(json) ?: continue
                    if (count > 0) output.write(",")
                    writeFeature(output, trail, toleranceM)
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

    private class Trail(val sac: SacScale, val vis: TrailVisibility?, val lons: DoubleArray, val lats: DoubleArray)

    private fun readElement(json: JsonReader): Trail? {
        var sac: SacScale? = null
        var vis: TrailVisibility? = null
        var coords: Pair<DoubleArray, DoubleArray>? = null
        json.beginObject()
        while (json.hasNext()) {
            when (json.nextName()) {
                "tags" -> {
                    json.beginObject()
                    while (json.hasNext()) {
                        when (json.nextName()) {
                            "sac" -> sac = SacScale.fromOsm(json.nextString())
                            "vis" -> vis = TrailVisibility.fromOsm(json.nextString())
                            else -> json.skipValue()
                        }
                    }
                    json.endObject()
                }
                "geometry" -> coords = readLineString(json)
                else -> json.skipValue()
            }
        }
        json.endObject()
        val (lons, lats) = coords ?: return null
        if (sac == null || lons.size < 2) return null
        return Trail(sac, vis, lons, lats)
    }

    private fun readLineString(json: JsonReader): Pair<DoubleArray, DoubleArray>? {
        var isLine = false
        val lons = ArrayList<Double>()
        val lats = ArrayList<Double>()
        json.beginObject()
        while (json.hasNext()) {
            when (json.nextName()) {
                "type" -> isLine = json.nextString() == "LineString"
                "coordinates" -> {
                    json.beginArray()
                    while (json.hasNext()) {
                        if (json.peek() != JsonToken.BEGIN_ARRAY) { json.skipValue(); continue }
                        json.beginArray()
                        lons += json.nextDouble()
                        lats += json.nextDouble()
                        while (json.hasNext()) json.skipValue()
                        json.endArray()
                    }
                    json.endArray()
                }
                else -> json.skipValue()
            }
        }
        json.endObject()
        return if (isLine) lons.toDoubleArray() to lats.toDoubleArray() else null
    }

    private fun writeFeature(out: Writer, trail: Trail, toleranceM: Double) {
        out.write("""{"type":"Feature","properties":{"s":${trail.sac.grade},"v":${trail.vis?.level ?: 0}},""")
        out.write(""""geometry":{"type":"LineString","coordinates":[""")
        Simplify.douglasPeucker(trail.lons, trail.lats, toleranceM).forEachIndexed { n, i ->
            if (n > 0) out.write(",")
            out.write("[")
            out.write(round5(trail.lons[i]))
            out.write(",")
            out.write(round5(trail.lats[i]))
            out.write("]")
        }
        out.write("]}}")
    }

    /** Five decimals ≈ 1 m. */
    private fun round5(v: Double): String = ((v * 1e5).roundToLong() / 1e5).toString()
}
