package app.sentrek.geo.avalanche

import app.sentrek.geo.TileMath
import app.sentrek.geo.gpx.Gpx
import app.sentrek.geo.overlay.VectorTile
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.Reader
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.tan

/**
 * An avalanche bulletin for one area, as its avalanche warning service published it (CAAML v6,
 * gathered for all EAWS services by avalanche.report). Only what the bulletin says.
 */
data class Bulletin(
    val regionId: String,
    val regionName: String?,
    /** The warning service, e.g. "AINEVA Lombardia", and its website. */
    val provider: String?,
    val website: String?,
    /** Valid from and until, in milliseconds; 0 when the bulletin doesn't say. */
    val validFrom: Long,
    val validUntil: Long,
    val ratings: List<Rating>,
    val problems: List<Problem>,
    val highlights: String?,
    /** The longer texts: about the avalanches, the snowpack. */
    val activity: String?,
    val snowpack: String?,
    /** How the danger is expected to change the day after: "increasing", "steady" or "decreasing". */
    val tendency: String?,
    /** What the service says about the next days, when it writes it out. */
    val outlook: String? = null,
) {
    /** The highest danger anywhere and at any time of the bulletin, 1 to 5. */
    val maxLevel: Int get() = ratings.maxOfOrNull { it.level } ?: 0

    /**
     * The danger level 1–5 for one band of heights and part of the day: above or below a
     * height in Europe ([elevation]), or one of North America's elevation [band]s.
     */
    data class Rating(val level: Int, val elevation: Elevation, val period: Period, val band: Band? = null)

    /** Avalanche problem: [type] as in CAAML ("wind_slab"…), [aspects] "N", "NE"… */
    data class Problem(
        val type: String,
        val aspects: List<String>,
        val elevation: Elevation,
        val period: Period,
        val comment: String? = null,
        /** North America: the elevation bands it's in. */
        val bands: List<Band> = emptyList(),
    )

    /** North America's elevation bands, highest first. */
    enum class Band { Alpine, Treeline, BelowTreeline }

    /**
     * The heights something applies to: above [lower] and/or below [upper], each metres
     * ("2000") or "treeline"; both null for every height.
     */
    data class Elevation(val lower: String?, val upper: String?) {
        val all get() = lower == null && upper == null
    }

    /** "earlier" and "later": a bulletin for a day that warms up (morning, afternoon). */
    enum class Period { AllDay, Earlier, Later }

    companion object {
        val LEVELS = listOf("low", "moderate", "considerable", "high", "very_high")
        val ASPECTS = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")

        /** The bulletin of [regionId] in a CAAML v6 file of a warning service; null when it has none with a danger level. */
        fun parse(reader: Reader, regionId: String): Bulletin? {
            val root = runCatching { JsonParser.parseReader(reader).asJsonObject }.getOrNull() ?: return null
            val bulletins = root.array("bulletins") ?: return null
            for (element in bulletins) {
                val b = element.takeIf { it.isJsonObject }?.asJsonObject ?: continue
                val region = b.array("regions")?.mapNotNull { it.obj() }?.firstOrNull { it.text("regionID") == regionId } ?: continue
                val ratings = b.array("dangerRatings").orEmpty().mapNotNull { r ->
                    val o = r.obj() ?: return@mapNotNull null
                    val level = LEVELS.indexOf(o.text("mainValue")) + 1
                    if (level <= 0) null else Rating(level, elevation(o), period(o))
                }
                // "no_snow", "no_rating": nothing to show.
                if (ratings.isEmpty()) return null
                val problems = b.array("avalancheProblems").orEmpty().mapNotNull { p ->
                    val o = p.obj() ?: return@mapNotNull null
                    val type = o.text("problemType") ?: return@mapNotNull null
                    val aspects = o.array("aspects").orEmpty().mapNotNull { a -> a.takeIf { it.isJsonPrimitive }?.asString?.uppercase() }
                    Problem(type, ASPECTS.filter { it in aspects }, elevation(o), period(o), o.text("comment")?.let(::plain))
                }
                val source = b.getAsJsonObject("source")?.getAsJsonObject("provider")
                val valid = b.getAsJsonObject("validTime")
                val activity = b.getAsJsonObject("avalancheActivity")
                return Bulletin(
                    regionId = regionId,
                    regionName = region.text("name"),
                    provider = source?.text("name"),
                    website = source?.text("website"),
                    validFrom = valid?.text("startTime")?.let(::time) ?: 0L,
                    validUntil = valid?.text("endTime")?.let(::time) ?: 0L,
                    ratings = ratings,
                    problems = problems,
                    highlights = activity?.text("highlights")?.let(::plain),
                    activity = activity?.text("comment")?.let(::plain),
                    snowpack = b.getAsJsonObject("snowpackStructure")?.text("comment")?.let(::plain),
                    tendency = b.array("tendency")?.firstNotNullOfOrNull { it.obj()?.text("tendencyType") },
                    outlook = b.array("tendency")?.firstNotNullOfOrNull { it.obj()?.text("comment") }?.let(::plain),
                )
            }
            return null
        }

        private fun elevation(o: JsonObject): Elevation {
            val e = o.getAsJsonObject("elevation") ?: return Elevation(null, null)
            return Elevation(e.text("lowerBound"), e.text("upperBound"))
        }

        private fun period(o: JsonObject) = when (o.text("validTimePeriod")) {
            "earlier" -> Period.Earlier
            "later" -> Period.Later
            else -> Period.AllDay
        }

        /** "2026-03-31T22:00:00+00:00", or without seconds. */
        private fun time(s: String): Long =
            Gpx.parseTime(s).takeIf { it != 0L } ?: if (s.length >= 16) Gpx.parseTime(s.substring(0, 16) + ":00" + s.substring(16)) else 0L

        /** The texts carry a little HTML: line breaks and the odd tag or entity. */
        internal fun plain(html: String): String? = html
            .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
            // Paragraphs, headings and list items each on a line of their own.
            .replace(Regex("</?(p|h[1-6]|li|div|ul|ol)(\\s[^>]*)?>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("<[^>]+>"), "")
            .replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'").replace("&amp;", "&")
            .lines().joinToString("\n") { it.trim() }
            .replace(Regex("\n{2,}"), "\n")
            .trim()
            .takeIf { it.isNotEmpty() }

        private fun JsonObject.array(key: String): JsonArray? = get(key)?.takeIf { it.isJsonArray }?.asJsonArray
        private fun JsonObject.text(key: String): String? = get(key)?.takeIf { it.isJsonPrimitive }?.asString?.trim()?.takeIf { it.isNotEmpty() }
        private fun JsonElement.obj(): JsonObject? = takeIf { it.isJsonObject }?.asJsonObject
        private fun JsonArray?.orEmpty(): List<JsonElement> = this?.toList() ?: emptyList()
    }
}

/**
 * Where the avalanche warning services' areas are: the EAWS micro-regions, as vector tiles in
 * one PMTiles file read in pieces (about 15 KB a tile), and the bulletin files they're in.
 */
object AvalancheRegions {
    const val PMTILES_URL = "https://static.avalanche.report/eaws-regions.pmtiles"
    const val BULLETINS_URL = "https://static.avalanche.report/eaws_bulletins"

    /** The file's most detailed tiles: about 40 km across, outlines to about 100 m. */
    const val ZOOM = 10
    private const val LAYER = "micro-regions"

    /** The tile [lat], [lon] is in, at [ZOOM]. */
    fun tile(lat: Double, lon: Double): Pair<Int, Int> = TileMath.tileX(lon, ZOOM) to TileMath.tileY(lat, ZOOM)

    /**
     * The micro-regions [lat], [lon] is in on [date] ("2026-11-13"), from its [tile]: areas are
     * redrawn now and then, each version with its dates. The regional services first: the
     * Italian Meteomont service covers the Alps too, but the regional bulletin is the one for them.
     */
    fun at(tile: ByteArray, lat: Double, lon: Double, date: String): List<String> {
        val n = 1 shl ZOOM
        val fx = (lon + 180.0) / 360.0 * n
        val latRad = Math.toRadians(lat.coerceIn(-TileMath.MAX_MERCATOR_LAT, TileMath.MAX_MERCATOR_LAT))
        val fy = (1.0 - ln(tan(latRad) + 1.0 / cos(latRad)) / PI) / 2.0 * n
        return VectorTile.polygons(tile, LAYER) { tags ->
            tags["id"] != null && (tags["start_date"] ?: "") <= date && (tags["end_date"]?.let { date < it } ?: true)
        }.filter { p -> p.contains((fx - kotlin.math.floor(fx)) * p.extent, (fy - kotlin.math.floor(fy)) * p.extent) }
            .mapNotNull { it.tags["id"] }
            .distinct()
            .sortedBy { if (it.startsWith(MULTI_REGION)) 1 else 0 }
    }

    /**
     * The services' file names that may hold [regionId]'s bulletin, likeliest first: each
     * service publishes one file for all its areas, named after a shorter id ("IT-25" for
     * "IT-25-SO-03", "IT-32-BZ" for "IT-32-BZ-01", "CH" for "CH-1114").
     */
    fun services(regionId: String): List<String> {
        val parts = regionId.split('-')
        return (parts.size - 1 downTo 1).map { parts.take(it).joinToString("-") }
    }

    /**
     * A service's bulletins for [date], in [language] when it publishes in it: each file is in
     * English (or the service's language) as it is, with copies in other languages beside it.
     */
    fun bulletinUrl(service: String, date: String, language: String?): String =
        "$BULLETINS_URL/$date/$date-$service" + (language?.let { ".$it" } ?: "") + ".json"

    private const val MULTI_REGION = "IT-MeteoMont"
}
