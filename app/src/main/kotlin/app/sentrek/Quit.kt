package app.sentrek

import android.content.Context
import android.content.Intent
import android.os.Process
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import app.sentrek.maps.DownloadService
import app.sentrek.record.FollowRepository
import app.sentrek.record.RecordingRepository
import app.sentrek.record.TrackingService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Ends the app (see app.sentrek.data.AppExit, which SentrekApp points here): a recording going on stops and is saved as it is, following
 * stops, the services and their notifications go, and the process ends. A map download left
 * halfway carries on the next time the app opens.
 */
@Singleton
class Quit @Inject constructor(
    @ApplicationContext private val context: Context,
    private val recording: RecordingRepository,
    private val following: FollowRepository,
) {
    private var quitting = false

    fun run() {
        if (quitting) return
        quitting = true
        MainScope().launch {
            runCatching { if (recording.state.value != null) recording.save(name = null) }
                .onFailure { Log.e(TAG, "Cannot save the recording", it) }
            following.stop()
            // With nothing left to do the tracking service stops itself, and sends the last
            // position text on its way out: given a moment to.
            withTimeoutOrNull(SERVICE_STOP_MS) { TrackingService.running.first { !it } }
            context.stopService(Intent(context, TrackingService::class.java))
            context.stopService(Intent(context, DownloadService::class.java))
            NotificationManagerCompat.from(context).cancelAll()
            Process.killProcess(Process.myPid())
        }
    }

    private companion object {
        const val TAG = "Quit"
        const val SERVICE_STOP_MS = 3_000L
    }
}
