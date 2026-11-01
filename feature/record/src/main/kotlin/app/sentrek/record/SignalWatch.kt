package app.sentrek.record

import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.Build
import android.telephony.PhoneStateListener
import android.telephony.ServiceState
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.content.ContextCompat
import app.sentrek.geo.LatLon
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Where the phone last had a signal. With someone hurt and no signal, the first question is
 * where to walk to make the call: often back to a ridge or a bend passed an hour ago. While
 * the tracking service runs, the phone's service state is followed and each fix with service
 * becomes the last place with signal. Kept on disk for a day, so a restart doesn't lose it.
 */
@Singleton
class SignalWatch @Inject constructor(@ApplicationContext private val context: Context) {
    /** A place where calls and texts could get out, and when. */
    data class Spot(val position: LatLon, val time: Long)

    /** [hasSignal] now; [last] the last place with signal, null when unknown. */
    data class State(val hasSignal: Boolean, val last: Spot?)

    private val saved = context.getSharedPreferences("signal", Context.MODE_PRIVATE)
    private val telephony by lazy { context.getSystemService(TelephonyManager::class.java) }
    private var inService = false
    private var callback: Any? = null

    private val _state = MutableStateFlow<State?>(null)

    /** Null while not watching: no recording or route, or a phone without a SIM slot. */
    val state: StateFlow<State?> = _state.asStateFlow()

    fun start() {
        if (callback != null) return
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)) return
        val manager = telephony ?: return
        runCatching {
            callback = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                object : TelephonyCallback(), TelephonyCallback.ServiceStateListener {
                    override fun onServiceStateChanged(state: ServiceState) = onService(state)
                }.also { manager.registerTelephonyCallback(ContextCompat.getMainExecutor(context), it) }
            } else {
                @Suppress("DEPRECATION")
                object : PhoneStateListener() {
                    @Deprecated("Deprecated in Java")
                    override fun onServiceStateChanged(state: ServiceState?) {
                        state?.let(::onService)
                    }
                }.also {
                    @Suppress("DEPRECATION")
                    manager.listen(it, PhoneStateListener.LISTEN_SERVICE_STATE)
                }
            }
        }.onFailure { Log.w(TAG, "Cannot follow the phone signal", it) }
        _state.value = State(inService, load())
    }

    fun stop() {
        _state.value?.last?.let(::save)
        val manager = telephony
        when (val c = callback) {
            null -> Unit
            is TelephonyCallback -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) manager?.unregisterTelephonyCallback(c)
            is PhoneStateListener -> @Suppress("DEPRECATION") manager?.listen(c, PhoneStateListener.LISTEN_NONE)
        }
        callback = null
        _state.value = null
    }

    /** With every fix: with signal, it's the last place known to have one. */
    fun onLocation(location: Location) {
        if (callback == null || !inService) return
        if (location.hasAccuracy() && location.accuracy > MAX_ACCURACY_M) return
        val spot = Spot(LatLon(location.latitude, location.longitude, LocationRepository.altitude(location)), location.time)
        _state.value = State(true, spot)
        // On disk now and then: the signal going away writes the last one anyway.
        if (spot.time - savedAt >= SAVE_EVERY_MS) save(spot)
    }

    private fun onService(state: ServiceState) {
        val was = inService
        inService = state.state == ServiceState.STATE_IN_SERVICE
        val last = _state.value?.last
        if (was && !inService && last != null) save(last)
        _state.value = State(inService, last ?: load())
    }

    /** When [save] last wrote, in fix time. */
    private var savedAt = 0L

    private fun save(spot: Spot) {
        savedAt = spot.time
        saved.edit()
            .putString(LAT, spot.position.lat.toString())
            .putString(LON, spot.position.lon.toString())
            .putString(ELE, spot.position.ele?.toString())
            .putLong(TIME, spot.time)
            .apply()
    }

    private fun load(): Spot? {
        val time = saved.getLong(TIME, 0)
        if (System.currentTimeMillis() - time > KEEP_MS) return null
        val lat = saved.getString(LAT, null)?.toDoubleOrNull() ?: return null
        val lon = saved.getString(LON, null)?.toDoubleOrNull() ?: return null
        return Spot(LatLon(lat, lon, saved.getString(ELE, null)?.toDoubleOrNull()), time)
    }

    private companion object {
        const val TAG = "SignalWatch"
        const val LAT = "lat"
        const val LON = "lon"
        const val ELE = "ele"
        const val TIME = "time"

        /** A place to walk back to should be a place, not a guess. */
        const val MAX_ACCURACY_M = 50f

        /** How often the last place with signal is written to disk while it keeps changing. */
        const val SAVE_EVERY_MS = 60_000L

        /** Yesterday's walk is no help today. */
        const val KEEP_MS = 24 * 60 * 60 * 1000L
    }
}
