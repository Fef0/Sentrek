package app.sentrek.data

import kotlin.math.max

/**
 * How often the GPS is asked for a fix: with the map on screen, and in the background (screen
 * off) while following a route or only recording. Chosen in Settings > Advanced.
 */
data class GpsTiming(
    val screenMs: Long = 1_000,
    val followMs: Long = 2_000,
    val recordMs: Long = 4_000,
) {
    /**
     * What applies in [mode]: battery saver never asks more often than [Saver], whatever was
     * chosen; stamina mode always uses [Stamina].
     */
    fun forMode(mode: PowerMode): GpsTiming = when (mode) {
        PowerMode.Normal -> this
        PowerMode.Saver -> GpsTiming(max(screenMs, Saver.screenMs), max(followMs, Saver.followMs), max(recordMs, Saver.recordMs))
        PowerMode.Stamina -> Stamina
    }

    companion object {
        val Default = GpsTiming()
        val Saver = GpsTiming(screenMs = 2_000, followMs = 5_000, recordMs = 10_000)

        /**
         * The slowest of each: a position every 5 s on screen; going off route noticed within
         * half a minute or so; a recorded point every 30 s (about 40 m at walking pace).
         */
        val Stamina = GpsTiming(screenMs = 5_000, followMs = 10_000, recordMs = 30_000)

        val SCREEN_CHOICES = listOf(1_000L, 2_000L, 5_000L)

        /** Following must notice going off route: a minute between fixes would be too late. */
        val FOLLOW_CHOICES = listOf(1_000L, 2_000L, 5_000L, 10_000L)

        /** Recording keeps a point every 5 m anyway: at walking pace about one in 4 s. */
        val RECORD_CHOICES = listOf(2_000L, 4_000L, 10_000L, 30_000L)
    }
}
