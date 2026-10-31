package app.sentrek.geo.overlay

import app.sentrek.geo.BoundingBox
import app.sentrek.geo.LatLon
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.Reader

/** The trail lines of the downloaded areas: the path network, for the junctions on a route. */
fun interface OverlayLineSource {
    suspend fun lines(box: BoundingBox): List<List<LatLon>>
}

object OverlayLines {
    /**
     * The trail lines of one overlay file that reach into [box]. Streamed, like [OverlayPois]:
     * points are skipped, and so is every line wholly outside the box.
     */
    fun read(input: Reader, box: BoundingBox): List<List<LatLon>> {
        val out = mutableListOf<List<LatLon>>()
        JsonReader(input).use { json ->
            json.beginObject()
            while (json.hasNext()) {
                if (json.nextName() != "features") { json.skipValue(); continue }
                json.beginArray()
                while (json.hasNext()) readFeature(json)?.filter { line -> line.any { it in box } }?.let(out::addAll)
                json.endArray()
            }
            json.endObject()
        }
        return out
    }

    private operator fun BoundingBox.contains(p: LatLon) = p.lat in south..north && p.lon in west..east

    private fun readFeature(json: JsonReader): List<List<LatLon>>? {
        var lines: List<List<LatLon>>? = null
        json.beginObject()
        while (json.hasNext()) {
            if (json.nextName() == "geometry") lines = readLines(json) else json.skipValue()
        }
        json.endObject()
        return lines
    }

    /** A LineString's or MultiLineString's lines; null for points ("type" comes first in our files). */
    private fun readLines(json: JsonReader): List<List<LatLon>>? {
        var type: String? = null
        var lines: List<List<LatLon>>? = null
        json.beginObject()
        while (json.hasNext()) {
            when (json.nextName()) {
                "type" -> type = json.nextString()
                "coordinates" -> lines = when (type) {
                    "LineString" -> listOf(readLine(json))
                    "MultiLineString" -> {
                        val all = mutableListOf<List<LatLon>>()
                        json.beginArray()
                        while (json.hasNext()) all += readLine(json)
                        json.endArray()
                        all
                    }
                    else -> { json.skipValue(); null }
                }
                else -> json.skipValue()
            }
        }
        json.endObject()
        return lines?.filter { it.size >= 2 }
    }

    private fun readLine(json: JsonReader): List<LatLon> {
        val points = mutableListOf<LatLon>()
        json.beginArray()
        while (json.hasNext()) {
            json.beginArray()
            val lon = json.nextDouble()
            val lat = json.nextDouble()
            while (json.peek() != JsonToken.END_ARRAY) json.skipValue()
            json.endArray()
            runCatching { LatLon(lat, lon) }.getOrNull()?.let(points::add)
        }
        json.endArray()
        return points
    }
}
