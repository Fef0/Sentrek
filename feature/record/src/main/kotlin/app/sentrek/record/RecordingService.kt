package app.sentrek.record

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.sentrek.feature.record.R
import app.sentrek.ui.Format
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Keeps GPS recording while the screen is off or another app is open. Started from the
 * foreground (user tapped Record, or the app reopened mid-recording), as Android requires
 * for location services; stops itself when the recording ends.
 */
@AndroidEntryPoint
class RecordingService : Service() {
    @Inject lateinit var recording: RecordingRepository
    @Inject lateinit var location: LocationRepository

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @OptIn(FlowPreview::class)
    override fun onCreate() {
        super.onCreate()
        createChannel()
        val started = runCatching {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, notification(recording.state.value),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0,
            )
        }
        if (started.isFailure) {
            // E.g. restarted by the system from the background: resumes when the app opens.
            Log.w(TAG, "Cannot record in the background now", started.exceptionOrNull())
            stopSelf()
            return
        }
        scope.launch { location.locations().collect(recording::onLocation) }
        scope.launch {
            recording.state.collect { if (it == null) stopSelf() }
        }
        scope.launch {
            // Android drops notification updates sent too often; a minute-level summary is enough.
            recording.state.sample(NOTIFICATION_INTERVAL_MS).distinctUntilChangedBy { it?.distance to it?.isPaused }.collect { state ->
                if (state != null && NotificationManagerCompat.from(this@RecordingService).areNotificationsEnabled()) {
                    NotificationManagerCompat.from(this@RecordingService).notify(NOTIFICATION_ID, notification(state))
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun notification(state: Recording?): Notification {
        val openApp = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE)
        }
        val text = state?.let {
            getString(R.string.record_notification_text, Format.distance(it.distance), Format.meters(it.ascent))
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_record)
            .setContentTitle(
                getString(if (state?.isPaused == true) R.string.record_notification_paused else R.string.record_notification_title),
            )
            .setContentText(text)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            // The chronometer can't skip pauses: it counts from a start time shifted by them.
            .setUsesChronometer(state != null && !state.isPaused)
            .setWhen(state?.let { System.currentTimeMillis() - it.activeMillis(System.currentTimeMillis()) } ?: System.currentTimeMillis())
            .setShowWhen(state != null && !state.isPaused)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID, getString(R.string.record_channel), NotificationManager.IMPORTANCE_LOW,
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        private const val TAG = "RecordingService"
        private const val CHANNEL_ID = "recording"
        private const val NOTIFICATION_ID = 2
        private const val NOTIFICATION_INTERVAL_MS = 10_000L

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, RecordingService::class.java))
        }
    }
}
