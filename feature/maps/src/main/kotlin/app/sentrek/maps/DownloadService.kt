package app.sentrek.maps

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
import kotlin.math.roundToInt

/**
 * Keeps the process alive while areas download, so leaving the app mid-download doesn't
 * stop it, and shows progress in a notification. Stops itself when nothing is pending.
 */
@AndroidEntryPoint
class DownloadService : Service() {
    @Inject lateinit var regions: RegionRepository

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        createChannel()
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, notification(regions.state.value.values),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
        )
        scope.launch {
            regions.state.collect { all ->
                if (all.values.none { it.isPending }) {
                    stopSelf()
                } else if (NotificationManagerCompat.from(this@DownloadService).areNotificationsEnabled()) {
                    NotificationManagerCompat.from(this@DownloadService).notify(NOTIFICATION_ID, notification(all.values))
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun notification(all: Collection<RegionState>): Notification {
        val left = all.count { it.isPending }
        val active = all.firstNotNullOfOrNull { it.map as? MapDownload.Downloading }
        val openApp = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE)
        }
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle(getString(R.string.download_notification_title))
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
        return if (active != null) {
            val percent = (active.fraction * 100).roundToInt()
            builder.setContentText(getString(R.string.download_notification_area, left, percent))
                .setProgress(100, percent, false)
                .build()
        } else {
            builder.setContentText(getString(R.string.download_notification_trails))
                .setProgress(0, 0, true)
                .build()
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID, getString(R.string.download_channel), NotificationManager.IMPORTANCE_LOW,
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "downloads"
        private const val NOTIFICATION_ID = 1

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, DownloadService::class.java))
        }
    }
}

/** Map tiles or trail details still to download. */
internal val RegionState.isPending: Boolean
    get() = map !is MapDownload.Ready || trails == TrailsDownload.Downloading
