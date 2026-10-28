package app.sentrek.record

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.core.location.GnssStatusCompat
import androidx.core.location.LocationListenerCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.location.LocationRequestCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * GPS fixes straight from the platform LocationManager: no Google Play Services, so it works
 * on F-Droid builds and de-Googled phones. The GPS runs only while someone collects.
 */
@Singleton
class LocationRepository @Inject constructor(@ApplicationContext private val context: Context) {
    private val manager = context.getSystemService(LocationManager::class.java)

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * A fix every [intervalMs] at most; completes at once without permission. With several
     * requests at once, Android serves them all at the fastest rate asked.
     */
    @SuppressLint("MissingPermission")
    fun locations(intervalMs: Long = INTERVAL_MS): Flow<Location> = callbackFlow {
        if (!hasPermission() || manager == null) {
            close()
            return@callbackFlow
        }
        val listener = LocationListenerCompat { trySend(it) }
        val request = LocationRequestCompat.Builder(intervalMs)
            .setQuality(LocationRequestCompat.QUALITY_HIGH_ACCURACY)
            .build()
        manager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            ?.takeIf { System.currentTimeMillis() - it.time < STALE_MS }
            ?.let { trySend(it) }
        LocationManagerCompat.requestLocationUpdates(
            manager, LocationManager.GPS_PROVIDER, request, listener, Looper.getMainLooper(),
        )
        awaitClose { LocationManagerCompat.removeUpdates(manager, listener) }
    }

    /** Whether location is switched on in the phone's settings, GPS included. */
    fun isGpsOn(): Boolean = manager != null &&
        LocationManagerCompat.isLocationEnabled(manager) &&
        manager.isProviderEnabled(LocationManager.GPS_PROVIDER)

    /**
     * How many satellites the GPS hears, while it runs. Indoors this stays near zero, which
     * tells the user why there's no position yet. Nothing without permission.
     */
    @SuppressLint("MissingPermission")
    fun satellites(): Flow<Satellites> = callbackFlow {
        if (!hasPermission() || manager == null) {
            close()
            return@callbackFlow
        }
        val callback = object : GnssStatusCompat.Callback() {
            override fun onSatelliteStatusChanged(status: GnssStatusCompat) {
                var heard = 0
                var used = 0
                for (i in 0 until status.satelliteCount) {
                    if (status.getCn0DbHz(i) > 0f) heard++
                    if (status.usedInFix(i)) used++
                }
                trySend(Satellites(heard, used))
            }

            override fun onStopped() {
                trySend(Satellites(0, 0))
            }
        }
        if (!LocationManagerCompat.registerGnssStatusCallback(manager, callback, Handler(Looper.getMainLooper()))) {
            close()
            return@callbackFlow
        }
        awaitClose { LocationManagerCompat.unregisterGnssStatusCallback(manager, callback) }
    }

    companion object {
        /** Callers pass their own (see GpsTiming); this is the on-screen default. */
        const val INTERVAL_MS = 1_000L
        private const val STALE_MS = 60_000L

        /**
         * Height above sea level. GPS gives height above the WGS84 ellipsoid, which in the Alps
         * is ~50 m off; Android 14+ converts it for us, older versions keep the raw value.
         */
        fun altitude(location: Location): Double? = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && location.hasMslAltitude() ->
                location.mslAltitudeMeters
            location.hasAltitude() -> location.altitude
            else -> null
        }
    }
}

/** Satellites with a signal ([heard]) and those in the current fix ([used]). */
data class Satellites(val heard: Int, val used: Int)
