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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import app.sentrek.data.SettingsRepository
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.math.roundToInt

/**
 * Keeps the GPS on while recording or following a route, with the screen off or another app
 * open. Started from the foreground (a user action, or the app reopening), as Android
 * requires for location services; stops itself when there's nothing left to do.
 */
@AndroidEntryPoint
class TrackingService : Service() {
    @Inject lateinit var recording: RecordingRepository
    @Inject lateinit var following: FollowRepository
    @Inject lateinit var location: LocationRepository
    @Inject lateinit var settings: SettingsRepository

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @OptIn(FlowPreview::class)
    override fun onCreate() {
        super.onCreate()
        createChannel()
        val started = runCatching {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, notification(recording.state.value, following.state.value),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0,
            )
        }
        if (started.isFailure) {
            // E.g. restarted by the system from the background: resumes when the app opens.
            Log.w(TAG, "Cannot track in the background now", started.exceptionOrNull())
            stopSelf()
            return
        }
        // While the screen is on, the map asks for a fix every second and this gets those too;
        // in the pocket, the slower rate here is all the GPS does.
        val interval = combine(following.state, settings.gpsTiming, settings.powerMode) { f, timing, mode ->
            timing.forMode(mode).let { if (f != null) it.followMs else it.recordMs }
        }.distinctUntilChanged()
        scope.launch {
            interval.collectLatest { ms ->
                location.locations(ms).collect {
                    recording.onLocation(it)
                    following.onLocation(it)
                }
            }
        }
        val both = combine(recording.state, following.state) { r, f -> r to f }
        scope.launch {
            both.collect { (r, f) -> if (r == null && f == null) stopSelf() }
        }
        scope.launch {
            // Android drops notification updates sent too often; every few seconds is plenty.
            both.sample(NOTIFICATION_INTERVAL_MS)
                .distinctUntilChanged { a, b -> summary(a.first, a.second) == summary(b.first, b.second) }
                .collect { (r, f) ->
                    val manager = NotificationManagerCompat.from(this@TrackingService)
                    if ((r != null || f != null) && manager.areNotificationsEnabled()) {
                        manager.notify(NOTIFICATION_ID, notification(r, f))
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

    /** What the notification shows; it's only updated when this changes. */
    private fun summary(r: Recording?, f: Following?): String = listOf(
        r?.let { "${it.isPaused} ${it.distance.roundToInt() / 10}" },
        f?.let { "${it.offRoute} ${it.remaining.distance.roundToInt() / 10}" },
    ).joinToString()

    private fun notification(r: Recording?, f: Following?): Notification {
        val openApp = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE)
        }
        val title = when {
            f?.offRoute == true -> getString(R.string.follow_notification_off_route, Format.distance(f.offset ?: 0.0))
            r?.isPaused == true -> getString(R.string.record_notification_paused)
            r != null -> getString(R.string.record_notification_title)
            else -> getString(R.string.follow_notification_title, f?.name.orEmpty())
        }
        val text = listOfNotNull(
            r?.let { getString(R.string.record_notification_text, Format.distance(it.distance), Format.elevation(it.ascent)) },
            f?.let { getString(R.string.follow_notification_left, Format.distance(it.remaining.distance), Format.duration(it.remaining.millis)) },
        ).joinToString(" · ")
        val showChronometer = r != null && !r.isPaused
        val now = System.currentTimeMillis()
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_record)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            // The chronometer can't skip pauses: it counts from a start time shifted by them.
            .setUsesChronometer(showChronometer)
            .setWhen(if (r != null) now - r.activeMillis(now) else now)
            .setShowWhen(showChronometer)
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
        private const val TAG = "TrackingService"
        private const val CHANNEL_ID = "recording"
        private const val NOTIFICATION_ID = 2
        private const val NOTIFICATION_INTERVAL_MS = 5_000L

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, TrackingService::class.java))
        }
    }
}
