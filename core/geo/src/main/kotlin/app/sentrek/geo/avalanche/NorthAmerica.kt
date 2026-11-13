package app.sentrek.geo.avalanche

import app.sentrek.geo.gpx.Gpx
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.Reader

/**
 * Forecast areas from GeoJSON (Polygons and MultiPolygons): which one a point is in. The
 * properties are kept as text for the forecasts that come with them (the US map layer).
 */
class GeoAreas(val areas: List<Area>) {
    class Area(val id: String, val properties: Map<String, String>, private val rings: List<DoubleArray>) {
        private val south = rings.minOf { r -> (1 until r.size step 2).minOf { r[it] } }
        private val north = rings.maxOf { r -> (1 until r.size step 2).maxOf { r[it] } }
        private val west = rings.minOf { r -> (0 until r.size step 2).minOf { r[it] } }
        private val east = rings.maxOf { r -> (0 until r.size step 2).maxOf { r[it] } }

        /** Even-odd over all rings: holes stay out, the parts of a MultiPolygon count. */
        fun contains(lat: Double, lon: Double): Boolean {
            if (lat < south || lat > north || lon < west || lon > east) return false
            var inside = false
            for (ring in rings) {
                val n = ring.size / 2
                var j = n - 1
                for (i in 0 until n) {
                    val xi = ring[2 * i]
                    val yi = ring[2 * i + 1]
                    val xj = ring[2 * j]
                    val yj = ring[2 * j + 1]
                    if ((yi > lat) != (yj > lat) && lon < (xj - xi) * (lat - yi) / (yj - yi) + xi) inside = !inside
                    j = i
                }
            }
            return inside
        }
    }

    fun at(lat: Double, lon: Double): Area? = areas.firstOrNull { it.contains(lat, lon) }

    companion object {
        fun read(reader: Reader): GeoAreas {
            val root = runCatching { JsonParser.parseReader(reader).asJsonObject }.getOrNull() ?: return GeoAreas(emptyList())
            val features = root.getAsJsonArray("features") ?: return GeoAreas(emptyList())
            return GeoAreas(
                features.mapNotNull { f ->
                    val o = f.obj() ?: return@mapNotNull null
                    val geometry = o.getAsJsonObject("geometry") ?: return@mapNotNull null
                    val polygons = when (geometry.text("type")) {
                        "Polygon" -> listOfNotNull(geometry.getAsJsonArray("coordinates"))
                        "MultiPolygon" -> geometry.getAsJsonArray("coordinates")?.mapNotNull { it.arr() }
                        else -> null
                    } ?: return@mapNotNull null
                    val rings = polygons.flatMap { p -> p.mapNotNull { r -> r.arr()?.let(::ring) } }.filter { it.size >= 6 }
                    if (rings.isEmpty()) return@mapNotNull null
                    val properties = o.getAsJsonObject("properties")?.entrySet()
                        ?.mapNotNull { (k, v) -> v.takeIf { it.isJsonPrimitive }?.asString?.let { k to it } }?.toMap().orEmpty()
                    val id = o.get("id")?.takeIf { it.isJsonPrimitive }?.asString ?: properties["id"] ?: return@mapNotNull null
                    Area(id, properties, rings)
                },
            )
        }

        private fun ring(points: JsonArray): DoubleArray {
            val out = DoubleArray(points.size() * 2)
            var n = 0
            for (p in points) {
                val xy = p.arr() ?: continue
                if (xy.size() < 2) continue
                out[n++] = xy[0].asDouble
                out[n++] = xy[1].asDouble
            }
            return out.copyOf(n)
        }
    }
}

/**
 * Avalanche Canada's forecasts (Parks Canada's among them): all of them in one file, each for
 * one of its areas ([GeoAreas] from its areas file). Danger by elevation band, for three days.
 */
object AvalancheCanada {
    const val API = "https://avcan-services-api.prod.avalanche.ca/forecasts"

    fun areasUrl(language: String) = "$API/$language/areas"
    fun productsUrl(language: String) = "$API/$language/products"

    /** The forecast for [areaId], today's and the next day's; null when there is none with a danger level. */
    fun parse(reader: Reader, areaId: String): Pair<Bulletin, Bulletin?>? {
        val products = runCatching { JsonParser.parseReader(reader).asJsonArray }.getOrNull() ?: return null
        val product = products.mapNotNull { it.obj() }.firstOrNull { it.getAsJsonObject("area")?.text("id") == areaId } ?: return null
        val report = product.getAsJsonObject("report") ?: return null
        val days = report.getAsJsonArray("dangerRatings")?.mapNotNull { it.obj() }.orEmpty()
        val today = days.getOrNull(0)?.let(::ratings).orEmpty()
        if (today.isEmpty()) return null
        val problems = report.getAsJsonArray("problems")?.mapNotNull { it.obj()?.let(::problem) }.orEmpty()
        val summaries = report.getAsJsonArray("summaries")?.mapNotNull { it.obj() }.orEmpty()
        fun summary(type: String) = summaries.firstOrNull { it.getAsJsonObject("type")?.text("value") == type }?.text("content")?.let(Bulletin::plain)
        val owner = product.getAsJsonObject("owner")
        val issued = report.text("dateIssued")?.let(::time) ?: 0L
        val until = report.text("validUntil")?.let(::time) ?: 0L
        val name = report.text("title")
        val now = Bulletin(
            regionId = areaId,
            regionName = name,
            provider = owner?.text("display"),
            website = product.text("url"),
            validFrom = issued,
            // A forecast is for its first day; the days after are its outlook.
            validUntil = days.getOrNull(1)?.getAsJsonObject("date")?.text("value")?.let(::time)?.takeIf { it > issued } ?: until,
            ratings = today,
            problems = problems,
            highlights = report.text("highlights")?.let(Bulletin::plain),
            activity = summary("avalanche-summary"),
            snowpack = summary("snowpack-summary"),
            tendency = null,
        )
        val next = days.getOrNull(1)?.let { day ->
            val ratings = ratings(day)
            if (ratings.isEmpty()) return@let null
            // The problems and texts are the forecast's, for all its days.
            now.copy(
                validFrom = day.getAsJsonObject("date")?.text("value")?.let(::time) ?: 0L,
                validUntil = days.getOrNull(2)?.getAsJsonObject("date")?.text("value")?.let(::time) ?: until,
                ratings = ratings,
            )
        }
        return now to next
    }

    private fun ratings(day: JsonObject): List<Bulletin.Rating> {
        val ratings = day.getAsJsonObject("ratings") ?: return emptyList()
        return BANDS.mapNotNull { (key, band) ->
            val level = ratings.getAsJsonObject(key)?.getAsJsonObject("rating")?.let { r -> level(r.text("value")) ?: level(r.text("display")) }
            level?.let { Bulletin.Rating(it, Bulletin.Elevation(null, null), Bulletin.Period.AllDay, band) }
        }
    }

    private fun problem(o: JsonObject): Bulletin.Problem? {
        val type = o.getAsJsonObject("type")?.let { it.text("value") ?: it.text("display") }?.let(NorthAmerica::problemType) ?: return null
        var aspects = emptyList<String>()
        var bands = emptyList<Bulletin.Band>()
        for (factor in o.getAsJsonArray("factors")?.mapNotNull { it.obj() }.orEmpty()) {
            val flags = factor.getAsJsonObject("graphic")?.text("id")?.let(::flags) ?: continue
            when (factor.getAsJsonObject("type")?.text("value")) {
                // aspect-1-1-1-1-0-0-0-0: N, NE, E, SE, S, SW, W, NW.
                "aspect" -> aspects = Bulletin.ASPECTS.filterIndexed { i, _ -> flags.getOrNull(i) == true }
                // elevation-0-0-1: below treeline, treeline, alpine.
                "elevation" -> bands = listOf(Bulletin.Band.BelowTreeline, Bulletin.Band.Treeline, Bulletin.Band.Alpine)
                    .filterIndexed { i, _ -> flags.getOrNull(i) == true }
            }
        }
        return Bulletin.Problem(
            type, aspects, Bulletin.Elevation(null, null), Bulletin.Period.AllDay,
            comment = o.text("comment")?.let(Bulletin::plain), bands = bands.sortedBy { it.ordinal },
        )
    }

    /** "aspect-1-0-1-…_en": the ones and zeros. */
    private fun flags(id: String): List<Boolean>? =
        id.substringBefore('_').split('-').drop(1).map { it == "1" }.takeIf { it.isNotEmpty() }

    private val BANDS = listOf("alp" to Bulletin.Band.Alpine, "tln" to Bulletin.Band.Treeline, "btl" to Bulletin.Band.BelowTreeline)
}

/**
 * The US avalanche centers (avalanche.org): their zones with today's danger in one map file, and
 * each zone's forecast on its own, with the danger by elevation band and the problems.
 */
object AvalancheUs {
    const val MAP_URL = "https://api.avalanche.org/v2/public/products/map-layer"

    fun forecastUrl(center: String, zone: String) =
        "https://api.avalanche.org/v2/public/product?type=forecast&center_id=$center&zone_id=$zone"

    /**
     * The forecast of [zone] (an area of the map file): from its forecast file when it has the
     * details, else the map's danger level alone. Null out of season or without a rating.
     */
    fun parse(reader: Reader?, zone: GeoAreas.Area): Pair<Bulletin, Bulletin?>? {
        val p = zone.properties
        val mapLevel = p["danger_level"]?.toDoubleOrNull()?.toInt()?.takeIf { it in 1..5 }
        val forecast = reader?.let { r -> runCatching { JsonParser.parseReader(r).asJsonObject }.getOrNull() }
        val danger = forecast?.getAsJsonArray("danger")?.mapNotNull { it.obj() }.orEmpty()
        fun ratings(day: String) = danger.firstOrNull { it.text("valid_day") == day }?.let { d ->
            listOf("upper" to Bulletin.Band.Alpine, "middle" to Bulletin.Band.Treeline, "lower" to Bulletin.Band.BelowTreeline).mapNotNull { (key, band) ->
                d.get(key)?.takeIf { it.isJsonPrimitive }?.asString?.toDoubleOrNull()?.toInt()?.takeIf { it in 1..5 }
                    ?.let { Bulletin.Rating(it, Bulletin.Elevation(null, null), Bulletin.Period.AllDay, band) }
            }
        }.orEmpty()
        val today = ratings("current").ifEmpty {
            listOfNotNull(mapLevel?.let { Bulletin.Rating(it, Bulletin.Elevation(null, null), Bulletin.Period.AllDay) })
        }
        if (today.isEmpty() || p["off_season"] == "true") return null
        val center = forecast?.getAsJsonObject("avalanche_center")
        val problems = forecast?.getAsJsonArray("forecast_avalanche_problems")?.mapNotNull { it.obj() }
            ?.sortedBy { it.get("rank")?.takeIf { r -> r.isJsonPrimitive }?.asInt ?: 0 }
            ?.mapNotNull(::problem).orEmpty()
        val now = Bulletin(
            regionId = zone.id,
            regionName = p["name"],
            provider = center?.text("name") ?: p["center"],
            website = p["link"] ?: center?.text("url"),
            validFrom = forecast?.text("published_time")?.let(::time) ?: p["start_date"]?.let(::time) ?: 0L,
            validUntil = forecast?.text("expires_time")?.let(::time) ?: p["end_date"]?.let(::time) ?: 0L,
            ratings = today,
            problems = problems,
            highlights = forecast?.text("bottom_line")?.let(Bulletin::plain) ?: p["travel_advice"]?.let(Bulletin::plain),
            activity = forecast?.text("hazard_discussion")?.let(Bulletin::plain),
            snowpack = null,
            tendency = null,
        )
        val tomorrow = ratings("tomorrow")
        val next = tomorrow.takeIf { it.isNotEmpty() && now.validUntil > 0 }?.let {
            now.copy(validFrom = now.validUntil, validUntil = now.validUntil + DAY_MS, ratings = it, problems = emptyList(), highlights = null)
        }
        return now to next
    }

    private fun problem(o: JsonObject): Bulletin.Problem? {
        val type = o.text("name")?.let(NorthAmerica::problemType) ?: return null
        // "north upper", "east middle"…: the slopes, and the elevation bands.
        val places = o.getAsJsonArray("location")?.mapNotNull { it.takeIf { e -> e.isJsonPrimitive }?.asString?.lowercase()?.split(' ') }.orEmpty()
        val aspects = places.mapNotNull { ASPECT_WORDS[it.firstOrNull()] }.toSet()
        val bands = places.mapNotNull { BAND_WORDS[it.lastOrNull()] }.toSet()
        return Bulletin.Problem(
            type, Bulletin.ASPECTS.filter { it in aspects }, Bulletin.Elevation(null, null), Bulletin.Period.AllDay,
            comment = o.text("discussion")?.let(Bulletin::plain), bands = bands.sortedBy { it.ordinal },
        )
    }

    private val ASPECT_WORDS = mapOf(
        "north" to "N", "northeast" to "NE", "east" to "E", "southeast" to "SE",
        "south" to "S", "southwest" to "SW", "west" to "W", "northwest" to "NW",
    )
    private val BAND_WORDS = mapOf("upper" to Bulletin.Band.Alpine, "middle" to Bulletin.Band.Treeline, "lower" to Bulletin.Band.BelowTreeline)
    private const val DAY_MS = 24 * 60 * 60 * 1000L
}

internal object NorthAmerica {
    /** The problem as the European bulletins name it, where they have it; North America's own otherwise. */
    fun problemType(name: String): String? {
        val key = name.lowercase().filter { it.isLetter() }
        return when (key) {
            "windslab", "windslabs" -> "wind_slab"
            "stormslab", "stormslabs" -> "storm_slab"
            "persistentslab", "persistentslabs" -> "persistent_slab"
            "deeppersistentslab", "deeppersistentslabs" -> "deep_persistent_slab"
            "wetslab", "wetslabs" -> "wet_slab"
            "wetloose", "loosewet" -> "loose_wet"
            "dryloose", "loosedry" -> "loose_dry"
            "cornice", "cornices", "corniceavalanche" -> "cornices"
            "glide", "glideavalanche", "glidingsnow", "glidingslab" -> "gliding_snow"
            "" -> null
            else -> name.lowercase().replace(' ', '_')
        }
    }
}

/** "3", "3:considerable", "considerable" or "3 - Considerable": the level 1–5. */
internal fun level(value: String?): Int? {
    val v = value?.trim()?.lowercase() ?: return null
    v.firstOrNull()?.digitToIntOrNull()?.takeIf { it in 1..5 }?.let { return it }
    return when {
        v.startsWith("low") -> 1
        v.startsWith("moderate") -> 2
        v.startsWith("considerable") -> 3
        v.startsWith("high") -> 4
        v.startsWith("extreme") || v.startsWith("very") -> 5
        else -> null
    }
}

/** ISO times, with or without seconds and milliseconds. */
internal fun time(s: String): Long {
    val clean = s.replace(Regex("\\.\\d+"), "")
    return Gpx.parseTime(clean).takeIf { it != 0L } ?: if (clean.length >= 16) Gpx.parseTime(clean.substring(0, 16) + ":00" + clean.substring(16)) else 0L
}

private fun JsonObject.text(key: String): String? = get(key)?.takeIf { it.isJsonPrimitive }?.asString?.trim()?.takeIf { it.isNotEmpty() }
private fun JsonElement.obj(): JsonObject? = takeIf { it.isJsonObject }?.asJsonObject
private fun JsonElement.arr(): JsonArray? = takeIf { it.isJsonArray }?.asJsonArray
