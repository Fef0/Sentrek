package app.sentrek.geo

/**
 * Reads a position typed or pasted into search, as people write them and as Sentrek's own
 * position texts do:
 * - decimal degrees, "37.74, 14.94" or "37.74 14.94", with a sign or N/S/E/W ("37.74N 14.94E");
 * - degrees and minutes, "37° 44.4' N 14° 56.4' E";
 * - degrees, minutes and seconds, "37° 44' 24.0" N, 14° 56' 24.0" E";
 * - a "geo:" link.
 * Null for anything else, so a name with a number in it isn't taken for a position: the whole
 * text must be the two coordinates, and plain whole numbers ("12 5") don't count.
 */
object CoordinateParser {
    private val PART = Regex(
        """([NSEW])?\s*([-+]?\d{1,3}(?:\.\d+)?)\s*(?:[°º]\s*)?(?:(\d{1,2}(?:\.\d+)?)\s*['′’]\s*)?(?:(\d{1,2}(?:\.\d+)?)\s*(?:["″”]|'')\s*)?([NSEW])?""",
    )
    private val SEPARATORS = Regex("""[\s,;/]*""")

    fun parse(text: String): LatLon? {
        val t = text.trim().uppercase().removePrefix("GEO:").substringBefore('?')
        if (t.isEmpty()) return null
        val parts = PART.findAll(t).filter { it.groupValues[2].isNotEmpty() }.toList()
        if (parts.size != 2) return null
        // Nothing else in the text but what separates the two.
        var rest = t
        for (p in parts.reversed()) rest = rest.removeRange(p.range)
        if (!SEPARATORS.matches(rest)) return null

        val values = parts.map { m ->
            val (before, deg, min, sec, after) = m.destructured
            val hemisphere = before.ifEmpty { after }.firstOrNull()
            val written = m.value
            // A plain integer is too likely a number in a name.
            if (min.isEmpty() && '.' !in deg && hemisphere == null && '°' !in written && 'º' !in written) return null
            var v = deg.toDouble().let { kotlin.math.abs(it) } + (min.toDoubleOrNull() ?: 0.0) / 60 + (sec.toDoubleOrNull() ?: 0.0) / 3600
            if (deg.startsWith("-") || hemisphere == 'S' || hemisphere == 'W') v = -v
            hemisphere to v
        }
        val (first, second) = values
        // Hemisphere letters say which is which; without them, latitude comes first.
        val swapped = first.first == 'E' || first.first == 'W' || second.first == 'N' || second.first == 'S'
        val lat = if (swapped) second.second else first.second
        val lon = if (swapped) first.second else second.second
        if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
        return LatLon(lat, lon)
    }
}
