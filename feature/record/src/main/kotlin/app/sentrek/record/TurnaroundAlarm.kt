package app.sentrek.record

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.text.format.DateFormat
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import app.sentrek.data.TurnaroundMode
import app.sentrek.feature.record.R
import app.sentrek.geo.LatLon
import app.sentrek.geo.Sun
import app.sentrek.geo.route.Turnaround
import java.util.Calendar
import java.util.Date

/**
 * The turnaround alarm: long buzzes and a notification that pops up when it's time to think
 * about turning back. Off unless the user turns it on. Driven by [TrackingService] every
 * minute, so it rings with the screen off and the phone in a pocket.
 */
class TurnaroundAlarm(private val context: Context) {
    private var turnaround = Turnaround()
    private var route: String? = null

    /**
     * [at]: the last position known, [following] the route if any. A new route starts the
     * "before dark" check over; the set time rings once while the service runs.
     */
    fun check(mode: TurnaroundMode, marginMinutes: Int, minuteOfDay: Int, at: LatLon?, following: Following?) {
        val now = System.currentTimeMillis()
        val key = following?.let { "${it.trackId} ${it.reversed}" }
        if (key != null && key != route) {
            route = key
            turnaround = Turnaround()
        }
        when (mode) {
            TurnaroundMode.Off -> Unit
            TurnaroundMode.BeforeDark -> {
                val sunset = at?.let { Sun.day(now, it.lat, it.lon)?.sunset }
                val arrival = following?.let { now + it.remaining.millis }
                if (turnaround.beforeDark(now, arrival, sunset, marginMinutes * 60_000L)) {
                    ring(
                        context.getString(R.string.turnaround_dark_title),
                        context.getString(R.string.turnaround_dark_text, time(arrival!!), time(sunset!!)),
                    )
                }
            }
            TurnaroundMode.AtTime -> {
                val alarm = Calendar.getInstance().apply {
                    timeInMillis = now
                    set(Calendar.HOUR_OF_DAY, minuteOfDay / 60)
                    set(Calendar.MINUTE, minuteOfDay % 60)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }.timeInMillis
                if (turnaround.atTime(now, alarm)) {
                    ring(context.getString(R.string.turnaround_time_title), context.getString(R.string.turnaround_time_text, time(alarm)))
                }
            }
        }
    }

    private fun time(millis: Long) = DateFormat.getTimeFormat(context).format(Date(millis))

    private fun ring(title: String, text: String) {
        vibrate()
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // High: it pops up over whatever is open. The buzzing is done here, as strong as
            // the off-route one, whatever the phone does with notifications.
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, context.getString(R.string.turnaround_channel), NotificationManager.IMPORTANCE_HIGH)
                    .apply { enableVibration(false) },
            )
        }
        val openApp = context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
            PendingIntent.getActivity(context, 0, it, PendingIntent.FLAG_IMMUTABLE)
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_record)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openApp)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
        runCatching { manager.notify(NOTIFICATION_ID, notification) }
    }

    private fun vibrate() {
        runCatching {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService(VibratorManager::class.java).defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createWaveform(PATTERN, -1))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(PATTERN, -1)
            }
        }.onFailure { Log.w(TAG, "Cannot vibrate", it) }
    }

    private companion object {
        const val TAG = "TurnaroundAlarm"
        const val CHANNEL_ID = "turnaround"
        const val NOTIFICATION_ID = 5

        /** Four long buzzes: unlike anything else the app does. */
        val PATTERN = longArrayOf(0, 1000, 400, 1000, 400, 1000, 400, 1000)
    }
}
