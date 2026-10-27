package app.sentrek.ui

import java.util.Locale
import kotlin.math.roundToInt

/** Glanceable numbers: few digits, units always shown. Metric only for now. */
object Format {
    fun distance(meters: Double): String = when {
        meters < 1_000 -> "${meters.roundToInt()} m"
        meters < 10_000 -> String.format(Locale.getDefault(), "%.2f km", meters / 1_000)
        else -> String.format(Locale.getDefault(), "%.1f km", meters / 1_000)
    }

    fun meters(meters: Double): String = "${meters.roundToInt()} m"

    /** 1:05:09, or 5:09 under an hour. */
    fun duration(millis: Long): String {
        val total = (millis / 1_000).coerceAtLeast(0)
        val h = total / 3_600
        val m = total % 3_600 / 60
        val s = total % 60
        return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s) else String.format(Locale.ROOT, "%d:%02d", m, s)
    }
}
