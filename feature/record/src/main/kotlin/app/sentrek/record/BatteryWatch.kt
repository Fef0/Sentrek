package app.sentrek.record

import android.content.Context
import android.os.BatteryManager
import app.sentrek.geo.power.BatteryForecast
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * How long the battery lasts at the rate it's going, while recording or following: read once
 * a minute by [TrackingService] (no receiver kept awake for it).
 */
@Singleton
class BatteryWatch @Inject constructor(@ApplicationContext private val context: Context) {
    /** [percent] now; [emptyAt] when it runs out, null until there's enough to tell. */
    data class State(val percent: Int, val emptyAt: Long?)

    private var forecast = BatteryForecast()
    private val _state = MutableStateFlow<State?>(null)

    /** Null while not watching, or charging. */
    val state: StateFlow<State?> = _state.asStateFlow()

    /** A reading now; [mode] is what the drain depends on: a change starts the forecast over. */
    fun sample(mode: Any) {
        val manager = context.getSystemService(BatteryManager::class.java) ?: return
        val percent = manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).takeIf { it in 0..100 } ?: return
        val charging = manager.isCharging
        forecast.add(System.currentTimeMillis(), percent, charging, mode)
        _state.value = if (charging) null else State(percent, forecast.emptyAt())
    }

    fun stop() {
        forecast = BatteryForecast()
        _state.value = null
    }

    companion object {
        /** Lasting less than this past the end of the route (or from now, without one) is worth a word. */
        const val SPARE_MS = 30 * 60_000L

        /** Without a route: a warning when less than this is left. */
        const val LOW_MS = 2 * 3_600_000L

        /**
         * Whether the battery runs out too soon: before the end of the route ([arrival]) and
         * some spare, or within a couple of hours without a route.
         */
        fun tooSoon(emptyAt: Long, arrival: Long?, now: Long): Boolean =
            if (arrival != null) emptyAt < arrival + SPARE_MS else emptyAt - now < LOW_MS
    }
}
