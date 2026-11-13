package app.sentrek.update

import android.app.job.JobParameters
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Runs [GitHubUpdater]'s check when Android lets it (network). */
@AndroidEntryPoint
class UpdateJobService : JobService() {
    @Inject lateinit var updater: GitHubUpdater

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val running = HashMap<Int, Job>()

    override fun onStartJob(params: JobParameters): Boolean {
        running[params.jobId] = scope.launch {
            if (params.jobId == GitHubUpdater.JOB_CHECK) updater.check()
            jobFinished(params, false)
        }
        return true
    }

    /** Stopped mid-way (the Wi-Fi went): the check is tried again. */
    override fun onStopJob(params: JobParameters): Boolean {
        running.remove(params.jobId)?.cancel()
        return params.jobId == GitHubUpdater.JOB_CHECK
    }
}

/** Android's answer to an install the updater started. */
@AndroidEntryPoint
class InstallResultReceiver : BroadcastReceiver() {
    @Inject lateinit var updater: GitHubUpdater

    override fun onReceive(context: Context, intent: Intent) {
        updater.onResult(intent)
    }
}
