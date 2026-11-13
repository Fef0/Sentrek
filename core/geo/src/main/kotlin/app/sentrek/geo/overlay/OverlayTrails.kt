package app.sentrek.geo.overlay

import app.sentrek.geo.BoundingBox
import app.sentrek.geo.LatLon
import app.sentrek.geo.plan.TrailLine
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.Reader

/**
 * The paths, tracks and via ferratas of a downloaded area's hiking overlay (see
 * [OverpassOverlay]), for the planner. The map only has what's on screen; the file has the
 * whole area, so a way round that leaves the screen can still be found.
 */
object OverlayTrails {
    /** Via ferratas cost this much per metre, as on the map's own lines: taken only when there's no walking way round. */
    const val FERRATA_COST = 3.0

    /** The lines of [input] (an overlay file) with a point inside [box]; route lines and points left out. */
    fun read(input: Reader, box: BoundingBox): List<TrailLine> {
        val out = ArrayList<TrailLine>()
        JsonReader(input).use { json ->
            json.beginObject()
            while (json.hasNext()) {
                if (json.nextName() != "features") { json.skipValue(); continue }
                json.beginArray()
                while (json.hasNext()) readFeature(json, box)?.let(out::add)
                json.endArray()
            }
            json.endObject()
        }
        return out
    }

    private fun readFeature(json: JsonReader, box: BoundingBox): TrailLine? {
        var grade: Int? = null
        var ferrata = false
        var route = false
        var points: List<LatLon>? = null
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
                            "s" -> grade = value.toDoubleOrNull()?.toInt()
                            "f" -> ferrata = true
                            OverpassOverlay.NETWORK -> route = true
                        }
                    }
                    json.endObject()
                }
                "geometry" -> points = line(json)
                else -> json.skipValue()
            }
        }
        json.endObject()
        val line = points?.takeIf { it.size >= 2 } ?: return null
        // Waymarked routes run over the paths already in the file: not lines of their own.
        if (route || line.none { it in box }) return null
        return TrailLine(line, if (ferrata) FERRATA_COST else 1.0, grade, ferrata)
    }

    /** A LineString's points; null for any other geometry. */
    private fun line(json: JsonReader): List<LatLon>? {
        var type: String? = null
        val points = ArrayList<LatLon>()
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
                            points += LatLon(lat, lon)
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
        return points.takeIf { type == "LineString" }
    }
}
