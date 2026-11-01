package app.sentrek.data

/**
 * How hard the app saves power. [Saver] is the battery saver switch; [Stamina] (Settings >
 * Advanced) is for survival situations only: everything at the bare minimum, battery saver
 * included and overridden.
 */
/**
 * Whether battery saver and stamina mode come on by themselves ([enabled]), at which battery
 * levels (percent), and whether each may still do so this time down (see PowerAutoSwitch).
 */
data class AutoPower(
    val enabled: Boolean = true,
    val saverAt: Int = DEFAULT_SAVER_AT,
    val staminaAt: Int = DEFAULT_STAMINA_AT,
    val saverArmed: Boolean = true,
    val staminaArmed: Boolean = true,
    /** The phone's own battery saver, as last seen: ours follows its changes. */
    val phoneSaverSeen: Boolean = false,
    /** Ours was turned on because the phone's was: it goes off with it. */
    val saverByPhone: Boolean = false,
) {
    companion object {
        const val DEFAULT_SAVER_AT = 25
        const val DEFAULT_STAMINA_AT = 10
        val SAVER_CHOICES = listOf(15, 20, 25, 30, 40)
        val STAMINA_CHOICES = listOf(5, 10, 15, 20)
    }
}

enum class PowerMode {
    Normal,
    Saver,
    Stamina;

    /**
     * Black screens and the night map, whatever the user chose: on OLED screens black pixels
     * use no power. Stamina mode only; battery saver keeps the user's theme and map.
     */
    val blackScreens: Boolean get() = this == Stamina

    /** The most frames per second the maps may draw, given the one chosen (0: no cap, [SMART_FPS]: smart). */
    fun mapFps(chosen: Int): Int = when (this) {
        Normal -> chosen
        // Battery saver caps it, unless a lower cap was chosen already.
        Saver -> if (chosen in 1..SAVER_FPS) chosen else SAVER_FPS
        Stamina -> STAMINA_FPS
    }

    companion object {
        /** Panning still looks fluid, at about half the drawing of a 60 Hz screen. */
        const val SAVER_FPS = 30

        /**
         * The bare minimum: the map jerks while it moves, but stays readable. Any lower and
         * finding a place takes longer, with the screen on all the while.
         */
        const val STAMINA_FPS = 10

        /**
         * As fast as the screen while a finger is on the map, [SAVER_FPS] the rest of the time
         * (also while the map follows the walk): the default.
         */
        const val SMART_FPS = -1

        /** The frame caps offered in Settings > Advanced (0: no cap). */
        val FPS_CHOICES = listOf(STAMINA_FPS, SAVER_FPS, 60, SMART_FPS, 0)
    }
}
