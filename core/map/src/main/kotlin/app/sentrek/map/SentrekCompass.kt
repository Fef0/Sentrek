package app.sentrek.map

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import android.view.Surface
import android.view.WindowManager
import org.maplibre.android.location.CompassEngine
import org.maplibre.android.location.CompassListener
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * The phone's compass, in place of MapLibre's, which reads the sensor ten times a second and
 * passes a heading on at most twice a second: the arrow and the compass strip lagged behind
 * the hand. In Normal mode it reads at game speed, smoothed just enough to take out the shake,
 * and passes on about thirty headings a second: as many as the arrow can draw. Battery saver and stamina read it slowly and pass only clear turns.
 *
 * Where the phone points: the top of the phone held flat, the back of it held up in front of
 * the eyes, as the screen is turned.
 */
internal class SentrekCompass(context: Context) : CompassEngine, SensorEventListener {
    private val sensors = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val window = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val rotationSensor: Sensor? = sensors.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val gravitySensor: Sensor? = sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val magneticSensor: Sensor? = sensors.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

    /** Slow and coarse in battery saver and stamina. */
    var saving: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            if (listeners.isNotEmpty()) {
                sensors.unregisterListener(this)
                register()
            }
        }

    private val listeners = mutableListOf<CompassListener>()
    private var heading = 0f
    private var known = false
    private var sentAt = 0L
    private var accuracy = SensorManager.SENSOR_STATUS_ACCURACY_HIGH

    // The heading smoothed as a direction (sine and cosine), so 359° and 1° average to 0°.
    private var smoothX = 0f
    private var smoothY = 0f
    private var smoothed = false

    private val rotation = FloatArray(9)
    private val remapped = FloatArray(9)
    private val upright = FloatArray(9)
    private val orientation = FloatArray(3)
    private val vector = FloatArray(4)
    private var gravity: FloatArray? = null
    private var magnetic: FloatArray? = null

    override fun addCompassListener(listener: CompassListener) {
        if (listeners.isEmpty()) register()
        listeners += listener
        // The last heading at once: a phone lying still may not report for a while.
        if (known) listener.onCompassChanged(heading)
    }

    override fun removeCompassListener(listener: CompassListener) {
        listeners -= listener
        // The last listener gone (map covered or stopped): the sensor goes off too.
        if (listeners.isEmpty()) {
            sensors.unregisterListener(this)
            smoothed = false
        }
    }

    override fun getLastHeading(): Float = heading

    override fun getLastAccuracySensorStatus(): Int = accuracy

    private fun register() {
        val delay = if (saving) SAVER_DELAY_US else SensorManager.SENSOR_DELAY_GAME
        if (rotationSensor != null) {
            sensors.registerListener(this, rotationSensor, delay)
        } else {
            gravitySensor?.let { sensors.registerListener(this, it, delay) }
            magneticSensor?.let { sensors.registerListener(this, it, delay) }
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ROTATION_VECTOR -> {
                // Some phones send more values than getRotationMatrixFromVector takes.
                val n = minOf(event.values.size, 4)
                System.arraycopy(event.values, 0, vector, 0, n)
                SensorManager.getRotationMatrixFromVector(rotation, if (n == 4) vector else event.values.copyOf(n))
            }
            Sensor.TYPE_ACCELEROMETER -> {
                gravity = event.values.clone()
                return update(fromFields = true)
            }
            Sensor.TYPE_MAGNETIC_FIELD -> {
                magnetic = event.values.clone()
                return update(fromFields = true)
            }
            else -> return
        }
        update(fromFields = false)
    }

    private fun update(fromFields: Boolean) {
        if (fromFields) {
            val g = gravity ?: return
            val m = magnetic ?: return
            if (!SensorManager.getRotationMatrix(rotation, null, g, m)) return
        }
        // As the screen is turned.
        @Suppress("DEPRECATION")
        when (window.defaultDisplay.rotation) {
            Surface.ROTATION_90 -> SensorManager.remapCoordinateSystem(rotation, SensorManager.AXIS_Y, SensorManager.AXIS_MINUS_X, remapped)
            Surface.ROTATION_180 -> SensorManager.remapCoordinateSystem(rotation, SensorManager.AXIS_MINUS_X, SensorManager.AXIS_MINUS_Y, remapped)
            Surface.ROTATION_270 -> SensorManager.remapCoordinateSystem(rotation, SensorManager.AXIS_MINUS_Y, SensorManager.AXIS_X, remapped)
            else -> System.arraycopy(rotation, 0, remapped, 0, 9)
        }
        SensorManager.getOrientation(remapped, orientation)
        // Held up rather than flat: the way the back of the phone faces.
        val pitch = orientation[1]
        if (abs(pitch) > UPRIGHT_RAD) {
            val z = if (pitch < 0) SensorManager.AXIS_Z else SensorManager.AXIS_MINUS_Z
            SensorManager.remapCoordinateSystem(remapped, SensorManager.AXIS_X, z, upright)
            SensorManager.getOrientation(upright, orientation)
        }
        val raw = orientation[0]
        val alpha = if (saving) 1f else SMOOTHING
        if (smoothed) {
            smoothX += alpha * (cos(raw) - smoothX)
            smoothY += alpha * (sin(raw) - smoothY)
        } else {
            smoothX = cos(raw)
            smoothY = sin(raw)
            smoothed = true
        }
        val degrees = ((Math.toDegrees(atan2(smoothY, smoothX).toDouble()) + 360) % 360).toFloat()

        if (known) {
            val now = SystemClock.elapsedRealtime()
            val turn = abs(((degrees - heading) % 360 + 540) % 360 - 180)
            val skip = if (saving) turn < SAVER_TURN_DEGREES || now - sentAt < SAVER_INTERVAL_MS
            else turn < NORMAL_TURN_DEGREES || now - sentAt < NORMAL_INTERVAL_MS
            if (skip) return
            sentAt = now
        }
        heading = degrees
        known = true
        listeners.toList().forEach { it.onCompassChanged(degrees) }
    }

    override fun onAccuracyChanged(sensor: Sensor?, status: Int) {
        if (status == accuracy) return
        accuracy = status
        listeners.toList().forEach { it.onCompassAccuracyChange(status) }
    }

    private companion object {
        /** Each reading moves the smoothed heading this share of the way: shake out, turns in. */
        const val SMOOTHING = 0.25f

        /** Tilted up more than this (45°), the phone is held up, not flat. */
        const val UPRIGHT_RAD = 0.785f

        /** About thirty a second; a still phone's tiny drift isn't passed on. */
        const val NORMAL_INTERVAL_MS = 33L
        const val NORMAL_TURN_DEGREES = 0.2f

        const val SAVER_DELAY_US = 200_000
        const val SAVER_TURN_DEGREES = 8f
        const val SAVER_INTERVAL_MS = 1_000L
    }
}
