package app.sentrek.maps

import android.annotation.SuppressLint
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
import android.os.PowerManager
import android.text.format.Formatter
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.sentrek.feature.maps.R
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Keeps the process alive while areas download, so leaving the app mid-download doesn't
 * stop it, and shows the progress in a notification. Stops itself when nothing is pending.
 * With the screen off it keeps the processor awake too: a download that sleeps with the phone
 * is a download that's still at 40% when the user reaches the trailhead.
 */
@AndroidEntryPoint
class DownloadService : Service() {
    @Inject lateinit var regions: RegionRepository

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var wakeLock: PowerManager.WakeLock? = null
    private var wakeLockAt = 0L

    override fun onCreate() {
        super.onCreate()
        DownloadNotifications.createChannel(this)
        ServiceCompat.startForeground(
            this, DownloadNotifications.PROGRESS_ID, DownloadNotifications.progress(this, regions.status.value),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
        )
        scope.launch {
            regions.status.collect { status ->
                if (status == null) {
                    stopSelf()
                    return@collect
                }
                holdWakeLock()
                if (NotificationManagerCompat.from(this@DownloadService).areNotificationsEnabled()) {
                    @SuppressLint("MissingPermission")
                    NotificationManagerCompat.from(this@DownloadService)
                        .notify(DownloadNotifications.PROGRESS_ID, DownloadNotifications.progress(this@DownloadService, status))
                }
            }
        }
    }

    /**
     * Held a while at a time and taken again as the download goes on: should anything keep
     * the service from stopping, the phone still gets to sleep.
     */
    private fun holdWakeLock() {
        val now = System.currentTimeMillis()
        if (wakeLock?.isHeld == true && now - wakeLockAt < WAKE_RENEW_MS) return
        val lock = wakeLock ?: getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Sentrek:maps").also { it.setReferenceCounted(false); wakeLock = it }
        lock.acquire(WAKE_HOLD_MS)
        wakeLockAt = now
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    // Android 15 gives a data download 6 hours a day in the background. Past that the service
    // must stop or the app is closed; the download carries on while the app is open, and from
    // where it was the next time it starts.
    override fun onTimeout(startId: Int) {
        Log.w(TAG, "Out of background time")
        stopSelf()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        Log.w(TAG, "Out of background time")
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        wakeLock?.takeIf { it.isHeld }?.release()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "DownloadService"
        private const val WAKE_HOLD_MS = 30 * 60_000L
        private const val WAKE_RENEW_MS = 10 * 60_000L

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, DownloadService::class.java))
        }
    }
}

/** The download notifications: progress while it runs, and how it went. */
object DownloadNotifications {
    private const val CHANNEL_ID = "downloads"
    const val PROGRESS_ID = 1
    // 2 to 8 belong to the walk's notifications (TrackingService and the alerts).
    private const val FINISHED_ID = 9

    /** Extra of the app's launch intent: open Maps, where the areas are. */
    const val EXTRA_OPEN_MAPS = "app.sentrek.OPEN_MAPS"

    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(CHANNEL_ID, context.getString(R.string.download_channel), NotificationManager.IMPORTANCE_LOW)
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun openMaps(context: Context): PendingIntent? =
        context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
            it.putExtra(EXTRA_OPEN_MAPS, true)
            PendingIntent.getActivity(context, 1, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }

    fun progress(context: Context, status: DownloadStatus?): Notification {
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentIntent(openMaps(context))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
        if (status == null) return builder.setContentTitle(context.getString(R.string.download_notification_title)).build()
        val res = context.resources
        return builder
            // Folded, only the title shows: what it waits for goes there.
            .setContentTitle(
                if (status.waiting == DownloadStatus.Waiting.Network) {
                    context.getString(R.string.download_title_offline, status.percent)
                } else {
                    res.getQuantityString(R.plurals.download_title, status.areas, status.areas) + " · ${status.percent}%"
                },
            )
            .setContentText(statusLine(context, status))
            .setProgress(100, status.percent, false)
            .build()
    }

    /** "1 of 3 done · Map 45% · 12 MB", or what it waits for. */
    fun statusLine(context: Context, status: DownloadStatus): String {
        val parts = ArrayList<String>()
        if (status.areas > 1) parts += context.getString(R.string.download_done_of, status.done, status.areas)
        parts += when (status.waiting) {
            DownloadStatus.Waiting.Network -> context.getString(R.string.download_waiting_network)
            DownloadStatus.Waiting.Server -> context.getString(R.string.download_waiting_server)
            null -> stepText(context, status)
        }
        return parts.joinToString(" · ")
    }

    fun stepText(context: Context, status: DownloadStatus): String = when (status.step) {
        DownloadStatus.Step.Map -> context.getString(R.string.download_step_map, Formatter.formatShortFileSize(context, status.mapBytes))
        DownloadStatus.Step.Trails -> context.getString(R.string.download_step_trails)
        DownloadStatus.Step.Pistes -> context.getString(R.string.download_step_pistes)
        DownloadStatus.Step.Cycling -> context.getString(R.string.download_step_cycling)
        DownloadStatus.Step.Queued -> context.getString(R.string.download_step_queued)
    }

    /** Once a batch is over: what's on the phone now, and what is missing. */
    @SuppressLint("MissingPermission")
    fun finished(context: Context, outcome: DownloadOutcome) {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return
        createChannel(context)
        val res = context.resources
        val text = if (outcome.failed > 0) {
            res.getQuantityString(R.plurals.download_finished_failed, outcome.failed, outcome.failed)
        } else {
            res.getQuantityString(R.plurals.download_finished_text, outcome.done, outcome.done)
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle(
                context.getString(
                    when {
                        outcome.failed == 0 -> R.string.download_finished_title
                        outcome.done == 0 -> R.string.download_finished_none
                        else -> R.string.download_finished_partly
                    },
                ),
            )
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openMaps(context))
            .setAutoCancel(true)
            .setSilent(true)
            .build()
        runCatching { manager.notify(FINISHED_ID, notification) }
    }

    /** A new batch: the last one's result is old news. */
    fun clearFinished(context: Context) {
        NotificationManagerCompat.from(context).cancel(FINISHED_ID)
    }
}
