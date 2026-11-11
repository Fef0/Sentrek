package app.sentrek.map

import android.graphics.PointF
import android.graphics.RectF
import app.sentrek.geo.overlay.OverpassExtras
import app.sentrek.geo.overlay.OverpassOverlay
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.sources.GeoJsonSource
import kotlin.math.cos
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.style.sources.VectorSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.LineString
import org.maplibre.geojson.MultiLineString
import org.maplibre.geojson.MultiPolygon
import org.maplibre.geojson.Point
import org.maplibre.geojson.Polygon
import kotlin.math.hypot

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
    /** One of the user's saved places. */
    val placeId: String? = null,
    /** Waymarked routes running here (hiking overlay only), most important first. */
    val routes: List<MapRoute> = emptyList(),
    /** Rivers, streams and lakes: whether the map data tags them intermittent. Null for anything else. */
    val intermittent: Boolean? = null,
    /** A path or track that crosses water without a bridge here. */
    val ford: Boolean = false,
    /** A piste's OSM piste:difficulty (novice, easy…) and piste:grooming (classic, skating…). */
    val pisteDifficulty: String? = null,
    val pisteGrooming: String? = null,
) {
    /** A river, stream or lake rather than a trail or a point. */
    val isWater: Boolean get() = intermittent != null
}

/** A waymarked route under a long press; [facts] are its OSM tags (from, to, distance…). */
data class MapRoute(
    val name: String?,
    /** The waymark as an osmc:symbol, to draw with [RouteMarks]. */
    val mark: String?,
    /** The routes it belongs to, nearest first: "Regione Sicilia V00", "Sentiero Italia". */
    val partOf: List<String>,
    val osmId: String,
    val facts: Map<String, String>,
)

/** Finds the map feature under a long press. */
object MapFeatures {
    /** Base style layers with points worth describing (hiking.json). */
    private val BASE_POINT_LAYERS = setOf("poi-hiking", "poi-landmarks", "poi-detail", "peak")

    /** Base style layers with the paths and tracks (hiking.json), and the cycling map's cycleways. */
    private val BASE_PATH_LAYERS = listOf("path", "track", MapExtras.CYCLEWAYS)
    private const val LIFT_LAYER = "aerialway"
    private val LIFT_KINDS = setOf("aerialway")
    private const val FORD_LAYER = "path-ford"

    /** Base style: rivers and streams, lakes, and the source with the lakes' names (hiking.json). */
    private const val WATERWAY_LAYER = "waterway"
    private const val WATER_LAYER = "water"
    private const val BASE_SOURCE = "openmaptiles"
    private const val WATER_NAME_LAYER = "water_name"
    private const val PATH_NAME_LAYER = "transportation_name"
    private val PATH_KINDS = setOf("path", "track")

    /** How far a path's label line may stray from the path itself (z14 tiles, simplified). */
    private const val NAME_REACH_M = 15.0

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
        val pointLayers = layerIds.filter { MapOverlays.isPointLayer(it) || it in BASE_POINT_LAYERS || it == TrackLayers.WAYPOINTS || it == PlaceLayers.LAYER }
        val hasTracks = TrackLayers.SHOWN in layerIds

        nearestPoint(map, exact, screen, pointLayers)?.let { return it }
        if (hasTracks) track(map, exact, at)?.let { return it }
        nearestPoint(map, near, screen, pointLayers)?.let { return it }
        if (hasTracks) track(map, near, at)?.let { return it }

        // On the winter map, pistes and lifts before the paths they cross.
        val pisteLines = layerIds.filter(MapExtras::isPisteLine)
        if (pisteLines.isNotEmpty()) {
            (piste(map, exact, at, pisteLines) ?: lift(map, exact, at, screen, radiusPx, layerIds)
                ?: piste(map, near, at, pisteLines) ?: lift(map, near, at, screen, radiusPx, layerIds))?.let { return it }
        }

        // A trail, with the routes on it; or a route on its own (on a road, say). The cycling
        // and winter maps don't draw the hiking routes: they're looked up in the files then.
        val routeLayers = layerIds.filter(MapOverlays::isRouteLayer)
        val hikingRoutes = if (routeLayers.isNotEmpty()) routes(map, near, routeLayers) else routesNear(map, screen, radiusPx)
        // On the cycling map, its own routes first: they name a road pressed on its own.
        val routes = cycleRoutes(map, near, layerIds.filter(MapExtras::isCycleRoute)) + hikingRoutes
        val trailLayers = layerIds.filter(MapOverlays::isTrailLayer)
        val trail = trailLayers.takeIf { it.isNotEmpty() }
            ?.let { map.queryRenderedFeatures(near, *it.toTypedArray()).firstOrNull() }
        if (trail == null && routes.isEmpty()) {
            // Squarely on a path or on water first: a stream beside a path, or under it, is
            // pressed for itself.
            val found = basePath(map, exact, near, at, layerIds) ?: ford(map, exact, at, layerIds) ?: water(map, exact, near, at, layerIds)
                ?: basePath(map, near, near, at, layerIds) ?: ford(map, near, at, layerIds) ?: water(map, near, near, at, layerIds)
                ?: lift(map, near, at, screen, radiusPx, layerIds)
                // Inside a ski area, off its runs: the area itself.
                ?: piste(map, exact, at, layerIds.filter(MapExtras::isPisteArea))
            return found?.let { if (it.kind in PATH_KINDS || it.kind == CYCLEWAY) it.copy(name = pathName(map, screen, radiusPx)) else it }
        }
        val props = trail?.let(::properties).orEmpty()
        return MapFeatureInfo(
            // The hiking overlay has no names: the base map's, as drawn along the path.
            name = if (trail != null) pathName(map, screen, radiusPx) else null,
            kind = if (trail != null) "path" else "route",
            position = at,
            osmId = props["id"],
            sacGrade = props["s"]?.toIntOrNull(),
            visibility = props["v"]?.toIntOrNull(),
            routes = routes,
            // Fords come with the base map, not the hiking overlay: looked up underneath.
            ford = trail != null && baseFord(map, near, layerIds),
        )
    }

    /**
     * A path or track of the base map: all there is where the area isn't downloaded. Its card
     * then says what's missing and how to get it, instead of the press offering a new place.
     */
    private fun basePath(map: MapLibreMap, box: RectF, near: RectF, at: LatLng, layerIds: List<String>): MapFeatureInfo? {
        val layers = BASE_PATH_LAYERS.filter { it in layerIds }.takeIf { it.isNotEmpty() } ?: return null
        val path = map.queryRenderedFeatures(box, *layers.toTypedArray()).firstOrNull() ?: return null
        return MapFeatureInfo(
            name = null,
            kind = if (path.getStringProperty("subclass") == CYCLEWAY) CYCLEWAY else path.getStringProperty("class") ?: "path",
            position = at,
            osmId = null,
            ford = baseFord(map, near, layerIds),
        )
    }

    /**
     * The name of the path or track under [screen], as the base map labels it: names are in
     * their own tile layer, apart from the lines, and drawn along a simplified copy of them, a
     * few metres off at close zoom. The nearest named line within [radiusPx], or [NAME_REACH_M].
     */
    private fun pathName(map: MapLibreMap, screen: PointF, radiusPx: Float, classes: Set<String> = PATH_KINDS): String? {
        val source = map.style?.getSourceAs<VectorSource>(BASE_SOURCE) ?: return null
        val lat = map.projection.fromScreenLocation(screen).latitude
        val reachPx = (NAME_REACH_M / map.projection.getMetersPerPixelAtLatitude(lat)).toFloat()
        var best: String? = null
        var bestPx = maxOf(radiusPx, reachPx)
        for (line in source.querySourceFeatures(arrayOf(PATH_NAME_LAYER), null)) {
            val p = properties(line)
            val name = p["name"] ?: continue
            if (p["class"] !in classes) continue
            val parts = when (val g = line.geometry()) {
                is LineString -> listOf(g.coordinates())
                is MultiLineString -> g.coordinates()
                else -> continue
            }
            for (part in parts) {
                val pts = part.map { map.projection.toScreenLocation(LatLng(it.latitude(), it.longitude())) }
                for (i in 1 until pts.size) {
                    val d = segmentDistance(screen, pts[i - 1], pts[i])
                    if (d < bestPx) {
                        bestPx = d
                        best = name
                    }
                }
            }
        }
        return best
    }

    private fun segmentDistance(p: PointF, a: PointF, b: PointF): Float {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val len = dx * dx + dy * dy
        val t = if (len == 0f) 0f else (((p.x - a.x) * dx + (p.y - a.y) * dy) / len).coerceIn(0f, 1f)
        return hypot(p.x - (a.x + t * dx), p.y - (a.y + t * dy))
    }

    /** A piste of the winter map: its name or number, kind, difficulty and grooming. */
    private fun piste(map: MapLibreMap, box: RectF, at: LatLng, layers: List<String>): MapFeatureInfo? {
        if (layers.isEmpty()) return null
        // Downhill first where runs share a way, as drawn.
        val p = map.queryRenderedFeatures(box, *layers.toTypedArray()).map(::properties)
            .minByOrNull { if (it["pt"] == "downhill") 0 else 1 }
            ?.filterValues { it.isNotBlank() } ?: return null
        return MapFeatureInfo(
            name = p["n"],
            kind = p["pt"]?.let { "piste_$it" } ?: "piste",
            position = at,
            osmId = p["id"],
            pisteDifficulty = p["pd"],
            pisteGrooming = p["g"],
        )
    }

    /** A ski lift or cable car of the base map, named as its label along it. */
    private fun lift(map: MapLibreMap, box: RectF, at: LatLng, screen: PointF, radiusPx: Float, layerIds: List<String>): MapFeatureInfo? {
        if (LIFT_LAYER !in layerIds) return null
        val lift = map.queryRenderedFeatures(box, LIFT_LAYER).firstOrNull() ?: return null
        return MapFeatureInfo(
            name = pathName(map, screen, radiusPx, LIFT_KINDS),
            kind = properties(lift)["subclass"] ?: "aerialway",
            position = at,
            osmId = null,
        )
    }

    /**
     * The hiking routes within [radiusPx], from the downloaded areas' files rather than the map:
     * the cycling and winter maps don't draw them. Distances in metres, without asking the map
     * to place every point on screen.
     */
    private fun routesNear(map: MapLibreMap, screen: PointF, radiusPx: Float): List<MapRoute> {
        val style = map.style ?: return emptyList()
        val at = map.projection.fromScreenLocation(screen)
        val reachM = radiusPx * map.projection.getMetersPerPixelAtLatitude(at.latitude)
        val kx = cos(Math.toRadians(at.latitude)) * M_PER_DEG
        fun x(p: Point) = (p.longitude() - at.longitude) * kx
        fun y(p: Point) = (p.latitude() - at.latitude) * M_PER_DEG
        val found = style.sources.filter { MapOverlays.isOverlaySource(it.id) }.filterIsInstance<GeoJsonSource>()
            .flatMap { it.querySourceFeatures(Expression.has(OverpassOverlay.NETWORK)) }
            .filter { feature ->
                val parts = when (val g = feature.geometry()) {
                    is LineString -> listOf(g.coordinates())
                    is MultiLineString -> g.coordinates()
                    else -> emptyList()
                }
                parts.any { part ->
                    part.zipWithNext().any { (a, b) ->
                        segmentDistance(PointF(0f, 0f), PointF(x(a).toFloat(), y(a).toFloat()), PointF(x(b).toFloat(), y(b).toFloat())) <= reachM
                    }
                }
            }
        return routeList(found)
    }

    /** Cycle and mountain bike routes under the press, on the cycling map. */
    private fun cycleRoutes(map: MapLibreMap, box: RectF, layers: List<String>): List<MapRoute> {
        if (layers.isEmpty()) return emptyList()
        return map.queryRenderedFeatures(box, *layers.toTypedArray())
            .map(::properties)
            .filter { "rid" in it }
            .distinctBy { it["rid"] }
            .sortedByDescending { it["w"]?.toDoubleOrNull() ?: 0.0 }
            .map { p ->
                MapRoute(
                    name = p["n"],
                    mark = null,
                    partOf = emptyList(),
                    osmId = "relation/${p["rid"]?.toDoubleOrNull()?.toLong() ?: p["rid"]}",
                    facts = p.filterKeys { it in CYCLE_FACTS },
                )
            }
    }

    /** A ford's icon itself, on a road too (roads aren't pressable otherwise). */
    private fun ford(map: MapLibreMap, box: RectF, at: LatLng, layerIds: List<String>): MapFeatureInfo? =
        if (baseFord(map, box, layerIds)) MapFeatureInfo(name = null, kind = "ford", position = at, osmId = null, ford = true) else null

    /** A ford's icon within reach: the path crosses water there (the icon marks each ford once). */
    private fun baseFord(map: MapLibreMap, box: RectF, layerIds: List<String>): Boolean =
        FORD_LAYER in layerIds && map.queryRenderedFeatures(box, FORD_LAYER).isNotEmpty()

    /**
     * A river or stream (its line), else a lake (its area). A lake's name isn't on its area
     * but on a label point of its own: the one that falls inside it.
     */
    private fun water(map: MapLibreMap, box: RectF, near: RectF, at: LatLng, layerIds: List<String>): MapFeatureInfo? {
        if (WATERWAY_LAYER in layerIds) {
            map.queryRenderedFeatures(box, WATERWAY_LAYER).firstOrNull()?.let { line ->
                val p = properties(line)
                return MapFeatureInfo(
                    name = p["name"],
                    kind = p["class"] ?: "stream",
                    position = at,
                    osmId = null,
                    intermittent = p["intermittent"] == "1",
                )
            }
        }
        if (WATER_LAYER !in layerIds) return null
        val area = map.queryRenderedFeatures(box, WATER_LAYER).firstOrNull() ?: return null
        val p = properties(area)
        // The sea is no place to hike to.
        if (p["class"] == "ocean") return null
        // A wide river's area has no label of its own: its name is on the line along it.
        val riverName = if (p["class"] == "river" && WATERWAY_LAYER in layerIds) {
            map.queryRenderedFeatures(near, WATERWAY_LAYER).firstNotNullOfOrNull { properties(it)["name"] }
        } else {
            null
        }
        return MapFeatureInfo(
            name = riverName ?: waterName(map, area),
            kind = p["class"] ?: "lake",
            position = at,
            osmId = null,
            intermittent = p["intermittent"] == "1",
        )
    }

    private fun waterName(map: MapLibreMap, area: Feature): String? {
        val source = map.style?.getSourceAs<VectorSource>(BASE_SOURCE) ?: return null
        // A lake is cut along the tile edges: all its pieces on screen, by its OSM id.
        val id = properties(area)["id"]
        val region = map.projection.visibleRegion
        val corner = region.farRight?.let(map.projection::toScreenLocation)
        val bottom = region.nearLeft?.let(map.projection::toScreenLocation)
        val pieces = if (id != null && corner != null && bottom != null) {
            map.queryRenderedFeatures(RectF(0f, 0f, corner.x, bottom.y), WATER_LAYER).filter { properties(it)["id"] == id }
        } else {
            emptyList()
        }.ifEmpty { listOf(area) }
        return source.querySourceFeatures(arrayOf(WATER_NAME_LAYER), null)
            .firstOrNull { label -> (label.geometry() as? Point)?.let { p -> pieces.any { inside(p, it) } } == true }
            ?.let { properties(it)["name"] }
    }

    private fun inside(p: Point, area: Feature): Boolean = when (val g = area.geometry()) {
        is Polygon -> inside(p, g.coordinates())
        is MultiPolygon -> g.coordinates().any { inside(p, it) }
        else -> false
    }

    /** In the outer ring and out of the holes (an island in the lake). */
    private fun inside(p: Point, rings: List<List<Point>>): Boolean =
        rings.isNotEmpty() && inRing(p, rings[0]) && rings.drop(1).none { inRing(p, it) }

    private fun inRing(p: Point, ring: List<Point>): Boolean {
        var inside = false
        var j = ring.lastIndex
        for (i in ring.indices) {
            val a = ring[i]
            val b = ring[j]
            if ((a.latitude() > p.latitude()) != (b.latitude() > p.latitude()) &&
                p.longitude() < (b.longitude() - a.longitude()) * (p.latitude() - a.latitude()) / (b.latitude() - a.latitude()) + a.longitude()
            ) {
                inside = !inside
            }
            j = i
        }
        return inside
    }

    private fun routes(map: MapLibreMap, box: RectF, layers: List<String>): List<MapRoute> {
        if (layers.isEmpty()) return emptyList()
        return routeList(map.queryRenderedFeatures(box, *layers.toTypedArray()))
    }

    private fun routeList(features: List<Feature>): List<MapRoute> =
        features
            .map(::properties)
            .filter { "rid" in it }
            .distinctBy { it["rid"] }
            .sortedByDescending { it["w"]?.toDoubleOrNull() ?: 0.0 }
            .map { p ->
                MapRoute(
                    name = p["n"],
                    mark = p["m"],
                    partOf = p["p"]?.split('\n').orEmpty(),
                    osmId = "relation/${p["rid"]?.toDoubleOrNull()?.toLong() ?: p["rid"]}",
                    facts = p.filterKeys { it in ROUTE_FACTS },
                )
            }

    private val ROUTE_FACTS = OverpassOverlay.ROUTE_FACTS.toSet()
    private val CYCLE_FACTS = OverpassExtras.CYCLE_FACTS.toSet()
    private const val CYCLEWAY = "cycleway"
    private const val M_PER_DEG = Math.PI / 180.0 * 6_371_000.0

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
        props["place"]?.let { id -> return MapFeatureInfo(props["name"], "place", position, osmId = null, placeId = id) }
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
