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
import kotlinx.coroutines.flow.MutableStateFlow
import app.sentrek.geo.track.RestDetector
import android.os.SystemClock
import android.os.PowerManager
import android.hardware.TriggerEventListener
import android.hardware.TriggerEvent
import android.hardware.SensorManager
import android.hardware.Sensor
import android.content.IntentFilter
import android.content.BroadcastReceiver
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
import app.sentrek.data.TurnaroundMode
import app.sentrek.data.PowerMode
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
    @Inject lateinit var signal: SignalWatch
    @Inject lateinit var battery: BatteryWatch
    @Inject lateinit var wet: WetWatch
    @Inject lateinit var shade: ShadeWatch

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val live by lazy { LiveNotification(this, CHANNEL_ID) }
    private val breadcrumbs by lazy { Breadcrumbs(this) }
    private val turnaround by lazy { TurnaroundAlarm(this) }

    /** The last fix as it came, and the settings of [breadcrumbs]. */
    private var fix: android.location.Location? = null
    private var breadcrumbMinutes = 0
    private var breadcrumbTo = ""
    private var breadcrumbName = ""
    private var lowBatterySms = true

    /** The turnaround alarm's settings. */
    private var turnaroundMode = TurnaroundMode.Off
    private var turnaroundMargin = 30
    private var turnaroundTime = 14 * 60

    /** What the battery drain depends on, and whether its warning was given on this walk. */
    private var powerMode = PowerMode.Normal
    private var batteryWarned = false

    /** Rain and storms on the way: off, no forecast is asked for at all. */
    private var rainAdvice = true
    private var shadeAdvice = true

    /** The last fix, for the time of sunset where the user is. */
    private var at: LatLon? = null
    private var shown: String? = null
    private var disarm: Job? = null

    /** When the notification was last sent, and its alerts then (see [update]). */
    private var notifiedAt = 0L
    private var notifiedAlerts: String? = null
    private val power by lazy { getSystemService(PowerManager::class.java) }

    /** The screen coming on: the notification catches up at once, it's about to be read. */
    private val screenOn = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            screenOff.value = intent.action == Intent.ACTION_SCREEN_OFF
            if (!screenOff.value) update()
        }
    }
    private val screenOff = MutableStateFlow(false)

    /**
     * A long stop (a break, a hut, a night out): the GPS slows down to [REST_GPS_MS] until
     * the user moves again, which the motion sensor says within seconds. Without that sensor
     * only a fix can tell, so they come every [REST_NO_SENSOR_GPS_MS].
     */
    private val rest = RestDetector()
    private val resting = MutableStateFlow(false)
    private val sensors by lazy { getSystemService(SensorManager::class.java) }
    private val motionSensor by lazy { sensors?.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION) }
    private val motion = object : TriggerEventListener() {
        override fun onTrigger(event: TriggerEvent?) {
            if (rest.moved()) resting.value = false
        }
    }

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
        screenOff.value = !power.isInteractive
        val request = combine(following.state, settings.gpsTiming, settings.powerMode, resting, screenOff) { f, timing, mode, still, off ->
            val ms = timing.forMode(mode).let { if (f != null) it.followMs else it.recordMs }
            val restMs = if (motionSensor != null) REST_GPS_MS else REST_NO_SENSOR_GPS_MS
            // Only recording, in the pocket: nothing needs the fixes at once (following does,
            // for the off-route and turn alerts).
            val batch = if (f == null && off) BATCH_MS else 0L
            (if (still) maxOf(ms, restMs) else ms) to batch
        }.distinctUntilChanged()
        scope.launch {
            request.collectLatest { (ms, batch) ->
                location.locations(ms, batch).collect {
                    at = runCatching { LatLon(it.latitude, it.longitude) }.getOrNull() ?: at
                    fix = it
                    onRestCheck(it)
                    signal.onLocation(it)
                    recording.onLocation(it)
                    following.onLocation(it)
                    checkBreadcrumbs()
                }
            }
        }
        breadcrumbs.start()
        signal.start()
        val screen = IntentFilter(Intent.ACTION_SCREEN_ON).apply { addAction(Intent.ACTION_SCREEN_OFF) }
        ContextCompat.registerReceiver(this, screenOn, screen, ContextCompat.RECEIVER_NOT_EXPORTED)
        // With the SOS screen off, its settings rest too (greyed out in Settings).
        scope.launch {
            combine(settings.sosEnabled, settings.breadcrumbMinutes) { sos, minutes -> if (sos) minutes else 0 }
                .collect { breadcrumbMinutes = it; checkBreadcrumbs() }
        }
        scope.launch { settings.emergencySmsTo.collect { breadcrumbTo = it; checkBreadcrumbs() } }
        scope.launch { settings.emergencyContactName.collect { breadcrumbName = it; checkBreadcrumbs() } }
        scope.launch { settings.lowBatterySms.collect { lowBatterySms = it; checkBreadcrumbs() } }
        scope.launch { settings.turnaround.collect { turnaroundMode = it } }
        scope.launch { settings.powerMode.collect { powerMode = it } }
        scope.launch { settings.rainAdvice.collect { rainAdvice = it; if (!it) wet.stop() } }
        scope.launch { settings.shadeAdvice.collect { shadeAdvice = it; if (it) shade.check(following.state.value) else shade.stop() } }
        scope.launch { settings.turnaroundMarginMinutes.collect { turnaroundMargin = it } }
        scope.launch { settings.turnaroundTime.collect { turnaroundTime = it } }
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
                turnaround.check(turnaroundMode, turnaroundMargin, turnaroundTime, at, following.state.value)
                checkBattery()
                if (rainAdvice) wet.check(at, following.state.value)
                if (shadeAdvice) shade.check(following.state.value)
            }
        }
    }

    /** Sends the notification again, when anything it shows has changed (or [force]). */
    private fun update(force: Boolean = false) {
        val r = recording.state.value
        val f = following.state.value
        val manager = NotificationManagerCompat.from(this)
        if ((r == null && f == null) || !manager.areNotificationsEnabled()) return
        // With the screen off nobody reads it: the numbers every half minute are plenty, but
        // what calls for attention (off route, paused, a tap to confirm) goes out at once.
        // Checked first, so the text isn't even put together in between.
        val alerts = "${r?.isPaused} ${f?.offRoute} ${live.armed}"
        val now = SystemClock.elapsedRealtime()
        if (!force && power?.isInteractive == false && alerts == notifiedAlerts && now - notifiedAt < SCREEN_OFF_NOTIFICATION_MS) return
        val summary = live.summary(r, f, at, System.currentTimeMillis())
        if (!force && summary == shown) return
        shown = summary
        notifiedAt = now
        notifiedAlerts = alerts
        manager.notify(NOTIFICATION_ID, notification(r, f))
    }

    /** Slows the GPS down during a long stop, and arms the motion sensor to wake it. */
    private fun onRestCheck(location: android.location.Location) {
        val here = runCatching { LatLon(location.latitude, location.longitude) }.getOrNull() ?: return
        if (!rest.update(here, location.time, location.accuracy.takeIf { location.hasAccuracy() })) return
        resting.value = rest.resting
        val sensor = motionSensor ?: return
        if (rest.resting) sensors?.requestTriggerSensor(motion, sensor) else sensors?.cancelTriggerSensor(motion, sensor)
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

    /** A battery reading, and once a walk, a word when at this rate it won't last. */
    private fun checkBattery() {
        battery.sample(powerMode)
        val empty = battery.state.value?.emptyAt ?: return
        val now = System.currentTimeMillis()
        val arrival = following.state.value?.let { now + it.remaining.millis }
        if (batteryWarned || !BatteryWatch.tooSoon(empty, arrival, now)) return
        batteryWarned = true
        val time = { millis: Long -> android.text.format.DateFormat.getTimeFormat(this).format(java.util.Date(millis)) }
        val text = if (arrival != null) {
            getString(R.string.battery_alert_route, time(empty), time(arrival))
        } else {
            getString(R.string.battery_alert_left, time(empty))
        }
        TrailAlerts.ring(this, BATTERY_NOTIFICATION_ID, getString(R.string.battery_alert_title), text, BATTERY_PATTERN)
    }

    private fun checkBreadcrumbs() =
        breadcrumbs.check(recording.state.value, fix, breadcrumbMinutes, breadcrumbTo, breadcrumbName, lowBatterySms)

    override fun onDestroy() {
        recording.flush()
        runCatching { unregisterReceiver(screenOn) }
        motionSensor?.let { sensors?.cancelTriggerSensor(motion, it) }
        if (recording.state.value == null) checkBreadcrumbs()
        breadcrumbs.stop()
        signal.stop()
        battery.stop()
        wet.stop()
        shade.stop()
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
        private const val BATTERY_NOTIFICATION_ID = 6

        /** Two long buzzes: worth a look, not an alarm. */
        private val BATTERY_PATTERN = longArrayOf(0, 600, 300, 600)
        private const val NOTIFICATION_INTERVAL_MS = 5_000L
        private const val CLOCK_INTERVAL_MS = 60_000L

        /** The GPS during a long stop, the motion sensor watching: a fix every five minutes. */
        private const val REST_GPS_MS = 300_000L
        private const val REST_NO_SENSOR_GPS_MS = 30_000L

        /** Recording in the pocket: fixes may wait this long to be handed over together. */
        private const val BATCH_MS = 60_000L

        /** Notification updates with the screen off, alerts aside. */
        private const val SCREEN_OFF_NOTIFICATION_MS = 30_000L
        private const val CONFIRM_MS = 5_000L
        private const val ACTION_TOGGLE = "app.sentrek.record.TOGGLE_PAUSE"

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, TrackingService::class.java))
        }
    }
}
