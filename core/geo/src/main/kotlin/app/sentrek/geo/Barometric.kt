package app.sentrek.geo

import kotlin.math.pow

/**
 * Altitude from air pressure (international standard atmosphere). The weather moves the
 * pressure as much as 100 m of climb, so the result is only good after [offset] has been set
 * from a known height; from then on it follows climbs and descents closely, with or without GPS.
 */
object Barometric {
    const val SEA_LEVEL_HPA = 1013.25

    /** Metres above the level where the pressure is [seaLevelHpa]. */
    fun altitude(hPa: Double, seaLevelHpa: Double = SEA_LEVEL_HPA): Double =
        44_330.0 * (1 - (hPa / seaLevelHpa).pow(1 / 5.255))

    /** What to add to [altitude] at [hPa] to read [knownMetres] there. */
    fun offset(hPa: Double, knownMetres: Double): Double = knownMetres - altitude(hPa)
}
