package app.sentrek.geo.overlay

import app.sentrek.geo.BoundingBox
import app.sentrek.geo.LatLon
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.Reader

/** A hiking point from a downloaded area's overlay (see [OverpassOverlay]). */
data class OverlayPoi(val kind: PoiKind, val name: String?, val position: LatLon)

/** Where the app keeps the overlays: the points of the downloaded areas inside a box. */
fun interface OverlayPoiSource {
    suspend fun pois(box: BoundingBox): List<OverlayPoi>
}

object OverlayPois {
    private val byIcon = PoiKind.entries.associateBy { it.icon }

    /**
     * The points of one overlay file inside [box], of the [kinds] wanted. Streamed: the files
     * are mostly trail lines, several megabytes, and only the points are kept.
     */
    fun read(input: Reader, box: BoundingBox, kinds: Set<PoiKind> = PoiKind.entries.toSet()): List<OverlayPoi> {
        val out = mutableListOf<OverlayPoi>()
        JsonReader(input).use { json ->
            json.beginObject()
            while (json.hasNext()) {
                if (json.nextName() != "features") { json.skipValue(); continue }
                json.beginArray()
                while (json.hasNext()) readFeature(json, box, kinds)?.let(out::add)
                json.endArray()
            }
            json.endObject()
        }
        return out
    }

    private fun readFeature(json: JsonReader, box: BoundingBox, kinds: Set<PoiKind>): OverlayPoi? {
        var kind: PoiKind? = null
        var name: String? = null
        var ele: Double? = null
        var position: LatLon? = null
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
                            "k" -> kind = byIcon[value]
                            "n" -> name = value
                            "ele" -> ele = value.replace(',', '.').trim().removeSuffix("m").trim().toDoubleOrNull()
                        }
                    }
                    json.endObject()
                }
                "geometry" -> position = readPoint(json)
                else -> json.skipValue()
            }
        }
        json.endObject()
        val k = kind?.takeIf { it in kinds } ?: return null
        val p = position ?: return null
        if (p.lat !in box.south..box.north || p.lon !in box.west..box.east) return null
        return OverlayPoi(k, name, p.copy(ele = ele))
    }

    /** A Point's position; null for lines (which are skipped without being kept). */
    private fun readPoint(json: JsonReader): LatLon? {
        var point = false
        var lon = Double.NaN
        var lat = Double.NaN
        json.beginObject()
        while (json.hasNext()) {
            when (json.nextName()) {
                "type" -> point = json.nextString() == "Point"
                // "type" comes first in our files, so a line's coordinates are skipped unread.
                "coordinates" -> if (point) {
                    json.beginArray()
                    lon = json.nextDouble()
                    lat = json.nextDouble()
                    while (json.hasNext()) json.skipValue()
                    json.endArray()
                } else {
                    json.skipValue()
                }
                else -> json.skipValue()
            }
        }
        json.endObject()
        return if (point && !lat.isNaN() && !lon.isNaN()) runCatching { LatLon(lat, lon) }.getOrNull() else null
    }
}
