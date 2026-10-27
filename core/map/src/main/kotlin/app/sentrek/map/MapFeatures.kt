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
)

/** Finds the map feature under a long press. */
object MapFeatures {
    /** Base style layers with points worth describing (hiking.json). */
    private val BASE_POINT_LAYERS = setOf("poi-hiking", "poi-landmarks", "poi-detail", "peak")

    /** Base tile properties that only drive the rendering. */
    private val BASE_HIDDEN = setOf("rank", "class", "subclass", "agg_stop", "level", "indoor", "layer", "ele_ft")

    /**
     * The point nearest to [screen] within [radiusPx] (generous, for gloves), or else the hiking
     * overlay trail under it. Null when there is nothing to describe.
     */
    fun at(map: MapLibreMap, screen: PointF, at: LatLng, radiusPx: Float): MapFeatureInfo? {
        val layerIds = map.style?.layers?.map { it.id } ?: return null
        val box = RectF(screen.x - radiusPx, screen.y - radiusPx, screen.x + radiusPx, screen.y + radiusPx)

        val pointLayers = layerIds.filter { MapOverlays.isPointLayer(it) || it in BASE_POINT_LAYERS }
        val nearest = map.queryRenderedFeatures(box, *pointLayers.toTypedArray())
            .mapNotNull { feature -> (feature.geometry() as? Point)?.let { feature to it } }
            .minByOrNull { (_, p) ->
                val s = map.projection.toScreenLocation(LatLng(p.latitude(), p.longitude()))
                (s.x - screen.x) * (s.x - screen.x) + (s.y - screen.y) * (s.y - screen.y)
            }
        if (nearest != null) {
            val (feature, p) = nearest
            return pointInfo(feature, LatLng(p.latitude(), p.longitude()))
        }

        val trailLayers = layerIds.filter(MapOverlays::isTrailLayer)
        if (trailLayers.isEmpty()) return null
        val trail = map.queryRenderedFeatures(box, *trailLayers.toTypedArray()).firstOrNull() ?: return null
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
