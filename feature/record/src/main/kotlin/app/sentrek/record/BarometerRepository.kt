package app.sentrek.record

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.emptyFlow
import javax.inject.Inject
import javax.inject.Singleton

/** The phone's pressure sensor, where there is one. Listened to only while collected. */
@Singleton
class BarometerRepository @Inject constructor(@ApplicationContext context: Context) {
    private val manager = context.getSystemService(SensorManager::class.java)
    private val sensor: Sensor? = manager?.getDefaultSensor(Sensor.TYPE_PRESSURE)

    val available: Boolean get() = sensor != null

    /** Air pressure in hPa, about once a second, averaged over a few readings against jitter. */
    fun pressures(): Flow<Double> {
        val s = sensor ?: return emptyFlow()
        val m = manager ?: return emptyFlow()
        return callbackFlow {
            var smoothed = Double.NaN
            val listener = object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent) {
                    val p = event.values.firstOrNull()?.toDouble() ?: return
                    smoothed = if (smoothed.isNaN()) p else smoothed + (p - smoothed) * SMOOTHING
                    trySend(smoothed)
                }

                override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
            }
            m.registerListener(listener, s, PERIOD_US)
            awaitClose { m.unregisterListener(listener) }
        }
    }

    private companion object {
        const val PERIOD_US = 1_000_000
        const val SMOOTHING = 0.3
    }
}
