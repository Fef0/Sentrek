package app.sentrek.map

import android.graphics.PointF
import android.graphics.RectF
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.geojson.Feature
import org.maplibre.geojson.Point

/**
 * What the user long-pressed. [kind] is the OSM value of what it is ("alpine_hut", "peak"…),
 * [tags] everything else we know about it, sorted by key.
 */
data class MapFeatureInfo(
    val name: String?,
    val kind: String?,
    val position: LatLng,
    /** OSM element, e.g. "node/123", when known (hiking overlay only). */
    val osmId: String?,
    /** SAC grade 1–6 and visibility 1–6 (0 = unknown), for trails only. */
    val sacGrade: Int? = null,
    val visibility: Int? = null,
    val tags: Map<String, String> = emptyMap(),
    /** A saved track shown on the map (its line, or one of its waypoints). */
    val trackId: String? = null,
)

/** Finds the map feature under a long press. */
object MapFeatures {
    /** Base style layers with points worth describing (hiking.json). */
    private val BASE_POINT_LAYERS = setOf("poi-hiking", "poi-landmarks", "poi-detail", "peak")

    /** Base tile properties that only drive the rendering. */
    private val BASE_HIDDEN = setOf("rank", "class", "subclass", "agg_stop", "level", "indoor", "layer", "ele_ft", "c", "track")

    /**
     * What's under the finger, in this order: an icon or a shown track right under it (within
     * [exactPx]), then the nearest icon or a track within [radiusPx], then a hiking overlay
     * trail. So a track pressed squarely wins over an icon that's merely within reach.
     * Null when there is nothing to describe.
     */
    fun at(map: MapLibreMap, screen: PointF, at: LatLng, radiusPx: Float, exactPx: Float): MapFeatureInfo? {
        val layerIds = map.style?.layers?.map { it.id } ?: return null
        val exact = box(screen, exactPx)
        val near = box(screen, radiusPx)
        val pointLayers = layerIds.filter { MapOverlays.isPointLayer(it) || it in BASE_POINT_LAYERS || it == TrackLayers.WAYPOINTS }
        val hasTracks = TrackLayers.SHOWN in layerIds

        nearestPoint(map, exact, screen, pointLayers)?.let { return it }
        if (hasTracks) track(map, exact, at)?.let { return it }
        nearestPoint(map, near, screen, pointLayers)?.let { return it }
        if (hasTracks) track(map, near, at)?.let { return it }

        val trailLayers = layerIds.filter(MapOverlays::isTrailLayer)
        if (trailLayers.isEmpty()) return null
        val trail = map.queryRenderedFeatures(near, *trailLayers.toTypedArray()).firstOrNull() ?: return null
        val props = properties(trail)
        return MapFeatureInfo(
            name = null,
            kind = "path",
            position = at,
            osmId = props["id"],
            sacGrade = props["s"]?.toIntOrNull(),
            visibility = props["v"]?.toIntOrNull(),
        )
    }

    private fun box(center: PointF, radius: Float) =
        RectF(center.x - radius, center.y - radius, center.x + radius, center.y + radius)

    private fun nearestPoint(map: MapLibreMap, box: RectF, screen: PointF, layers: List<String>): MapFeatureInfo? {
        if (layers.isEmpty()) return null
        val (feature, p) = map.queryRenderedFeatures(box, *layers.toTypedArray())
            .mapNotNull { feature -> (feature.geometry() as? Point)?.let { feature to it } }
            .minByOrNull { (_, p) ->
                val s = map.projection.toScreenLocation(LatLng(p.latitude(), p.longitude()))
                (s.x - screen.x) * (s.x - screen.x) + (s.y - screen.y) * (s.y - screen.y)
            } ?: return null
        return pointInfo(feature, LatLng(p.latitude(), p.longitude()))
    }

    private fun track(map: MapLibreMap, box: RectF, at: LatLng): MapFeatureInfo? {
        val track = map.queryRenderedFeatures(box, TrackLayers.SHOWN).firstOrNull() ?: return null
        return MapFeatureInfo(name = null, kind = "track", position = at, osmId = null, trackId = properties(track)["id"])
    }

    private fun pointInfo(feature: Feature, position: LatLng): MapFeatureInfo {
        val props = properties(feature)
        return if ("k" in props) {
            // Hiking overlay: k = icon, n = name, id = OSM element, the rest are OSM tags.
            val tags = props - setOf("k", "n", "id")
            MapFeatureInfo(
                name = props["n"],
                kind = PRIMARY_KEYS.firstNotNullOfOrNull { tags[it] } ?: props["k"],
                position = position,
                osmId = props["id"],
                tags = tags.toSortedMap(),
            )
        } else {
            // Base tiles (OpenMapTiles): name, class/subclass, ele…; name:xx are translations.
            MapFeatureInfo(
                name = props["name"],
                kind = props["subclass"] ?: props["class"],
                position = position,
                osmId = null,
                tags = props.filterKeys { !it.startsWith("name") && it !in BASE_HIDDEN }.toSortedMap(),
                trackId = props["track"],
            )
        }
    }

    /**
     * Tags whose value says best what a hiking point is, most specific first: a summit cross
     * (natural=peak + man_made=cross) is a "cross", a bivouac (amenity=shelter) a "basic_hut".
     */
    private val PRIMARY_KEYS = listOf(
        "information", "shelter_type", "historic", "man_made", "tourism", "waterway", "natural", "amenity", "emergency",
    )

    private fun properties(feature: Feature): Map<String, String> {
        val json = feature.properties() ?: return emptyMap()
        return json.entrySet()
            .filter { (_, v) -> v.isJsonPrimitive }
            .associate { (k, v) -> k to v.asString }
    }
}
