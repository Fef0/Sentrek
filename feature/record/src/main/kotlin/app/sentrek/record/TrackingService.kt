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
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import app.sentrek.geo.LatLon
import app.sentrek.data.SettingsRepository
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch
import javax.inject.Inject

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
    private val live by lazy { LiveNotification(this, CHANNEL_ID) }
    private val breadcrumbs by lazy { Breadcrumbs(this) }

    /** The last fix as it came, and the settings of [breadcrumbs]. */
    private var fix: android.location.Location? = null
    private var breadcrumbMinutes = 0
    private var breadcrumbTo = ""
    private var breadcrumbName = ""

    /** The last fix, for the time of sunset where the user is. */
    private var at: LatLon? = null
    private var shown: String? = null
    private var disarm: Job? = null

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
                    at = runCatching { LatLon(it.latitude, it.longitude) }.getOrNull() ?: at
                    fix = it
                    recording.onLocation(it)
                    following.onLocation(it)
                    checkBreadcrumbs()
                }
            }
        }
        breadcrumbs.start()
        // With the SOS screen off, its settings rest too (greyed out in Settings).
        scope.launch {
            combine(settings.sosEnabled, settings.breadcrumbMinutes) { sos, minutes -> if (sos) minutes else 0 }
                .collect { breadcrumbMinutes = it; checkBreadcrumbs() }
        }
        scope.launch { settings.emergencySmsTo.collect { breadcrumbTo = it; checkBreadcrumbs() } }
        scope.launch { settings.emergencyContactName.collect { breadcrumbName = it; checkBreadcrumbs() } }
        // The last text as the recording ends, before the service may stop.
        scope.launch { recording.state.collect { if (it == null) checkBreadcrumbs() } }
        val both = combine(recording.state, following.state) { r, f -> r to f }
        scope.launch {
            both.collect { (r, f) -> if (r == null && f == null) stopSelf() }
        }
        scope.launch {
            // Android drops notification updates sent too often; every few seconds is plenty.
            both.sample(NOTIFICATION_INTERVAL_MS).collect { update() }
        }
        scope.launch {
            // The time of sunset comes closer without anything else changing.
            while (true) {
                delay(CLOCK_INTERVAL_MS)
                update()
                // Also when no fix comes (a gorge, a tunnel): the last one known is sent.
                checkBreadcrumbs()
            }
        }
    }

    /** Sends the notification again, when anything it shows has changed (or [force]). */
    private fun update(force: Boolean = false) {
        val r = recording.state.value
        val f = following.state.value
        val manager = NotificationManagerCompat.from(this)
        if ((r == null && f == null) || !manager.areNotificationsEnabled()) return
        val summary = live.summary(r, f, at, System.currentTimeMillis())
        if (!force && summary == shown) return
        shown = summary
        manager.notify(NOTIFICATION_ID, notification(r, f))
    }

    /**
     * Pause/Resume from the notification. A pocket can press buttons too, so the first tap only
     * arms it ("Tap again to pause") for a few seconds and the second one acts.
     */
    private fun toggle() {
        val r = recording.state.value ?: return
        disarm?.cancel()
        if (!live.armed) {
            live.armed = true
            disarm = scope.launch {
                delay(CONFIRM_MS)
                live.armed = false
                update()
            }
        } else {
            live.armed = false
            if (r.isPaused) recording.resume() else recording.pause()
        }
        update(force = true)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_TOGGLE) toggle()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun checkBreadcrumbs() =
        breadcrumbs.check(recording.state.value, fix, breadcrumbMinutes, breadcrumbTo, breadcrumbName)

    override fun onDestroy() {
        if (recording.state.value == null) checkBreadcrumbs()
        breadcrumbs.stop()
        scope.cancel()
        super.onDestroy()
    }

    private fun notification(r: Recording?, f: Following?): Notification {
        val openApp = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE)
        }
        val toggle = PendingIntent.getService(
            this, 1, Intent(this, TrackingService::class.java).setAction(ACTION_TOGGLE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return live.build(r, f, at, System.currentTimeMillis(), openApp, toggle)
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        // Normal importance, but without sound or vibration: a low-importance ("silent")
        // notification is hidden from the lock screen by default, and that's where it's read.
        val channel = NotificationChannel(
            CHANNEL_ID, getString(R.string.record_channel), NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            setSound(null, null)
            enableVibration(false)
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
        // Its importance can't be raised once created, so the channel of version 0.10 goes.
        manager.deleteNotificationChannel(OLD_CHANNEL_ID)
    }

    companion object {
        private const val TAG = "TrackingService"
        private const val CHANNEL_ID = "tracking"
        private const val OLD_CHANNEL_ID = "recording"
        private const val NOTIFICATION_ID = 2
        private const val NOTIFICATION_INTERVAL_MS = 5_000L
        private const val CLOCK_INTERVAL_MS = 60_000L
        private const val CONFIRM_MS = 5_000L
        private const val ACTION_TOGGLE = "app.sentrek.record.TOGGLE_PAUSE"

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, TrackingService::class.java))
        }
    }
}
