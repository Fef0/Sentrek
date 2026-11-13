package app.sentrek.update

import android.app.Activity
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.FileProvider
import androidx.core.content.IntentCompat
import androidx.core.content.pm.PackageInfoCompat
import app.sentrek.BuildConfig
import app.sentrek.R
import app.sentrek.data.SettingsRepository
import app.sentrek.maps.isUnmetered
import app.sentrek.record.FollowRepository
import app.sentrek.record.RecordingRepository
import dagger.Lazy
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Looks at the latest GitHub release now and then (on Wi-Fi, unless mobile data is allowed)
 * and says when there is a new version: a notification, and a question when the app is open.
 * Only when the user says update, the APK for this phone's CPU is downloaded, checked, and
 * handed to Android's installer, which asks to confirm and offers to open the app again after.
 * Never during a recording or a followed route: installing closes the app.
 */
@Singleton
class GitHubUpdater @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
    // Lazy: made at app start, before the map engine these need is ready.
    private val recorder: Lazy<RecordingRepository>,
    private val follow: Lazy<FollowRepository>,
) : AppUpdater {
    override val available = true

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prefs = context.getSharedPreferences("updates", Context.MODE_PRIVATE)
    private val apk = File(context.filesDir, "update/Sentrek.apk")
    private val jobs: JobScheduler get() = context.getSystemService(JobScheduler::class.java)
    private val http by lazy {
        OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
    }

    private val _check = MutableStateFlow<UpdateCheck>(UpdateCheck.Idle)
    override val check: StateFlow<UpdateCheck> = _check.asStateFlow()

    private val _newVersion = MutableStateFlow<NewVersion?>(null)
    override val newVersion: StateFlow<NewVersion?> = _newVersion.asStateFlow()

    /** The update or check the user asked for, while it runs. */
    private var running: Job? = null
    private val fetching = Any()

    /** The latest release seen, for its download links. */
    @Volatile private var release: Release? = null

    @Volatile private var enabled = false
    @Volatile private var mobileData = false

    /** Sent to Android's "install unknown apps" page: goes on once back, if allowed. */
    @Volatile private var askedPermission = false

    /** The version downloaded and checked, if its file is still there. */
    private val ready: String?
        get() = prefs.getString(KEY_READY, null)?.takeIf { apk.exists() }

    override fun start() {
        (context.applicationContext as Application).registerActivityLifecycleCallbacks(Callbacks())
        // The install job of versions that installed by themselves: no more.
        jobs.cancel(OLD_JOB_INSTALL)
        forgetInstalled()
        _newVersion.value = prefs.getString(KEY_FOUND, null)?.takeIf { Versions.isNewer(it, BuildConfig.VERSION_NAME) }
            ?.let { NewVersion(it, prefs.getString(KEY_CHANGES, null)?.split('\n')?.filter(String::isNotBlank).orEmpty()) }
        scope.launch {
            combine(settings.autoUpdateApp, settings.useMobileData, ::Pair).collect { (on, mobile) ->
                enabled = on
                mobileData = mobile
                if (on) scheduleCheck() else jobs.cancel(JOB_CHECK)
            }
        }
    }

    override fun update(activity: Activity, overMobileData: Boolean) {
        val version = _newVersion.value?.version ?: return
        if (running?.isActive == true) return
        // Installing closes the app: not in the middle of a hike.
        if (hiking()) {
            _check.value = UpdateCheck.AfterHike(version)
            return
        }
        if (ready != version && !overMobileData && !mobileData && !isUnmetered(context)) {
            _check.value = UpdateCheck.OnMobileData(version)
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
            askedPermission = true
            val page = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            runCatching { activity.startActivity(page) }.onFailure { askedPermission = false }
            return
        }
        running = scope.launch {
            _check.value = UpdateCheck.Downloading(version, 0f)
            val latest = release?.takeIf { it.version == version } ?: runCatching { latest() }.getOrNull()
            if (latest == null) {
                _check.value = UpdateCheck.Failed
                return@launch
            }
            // A newer one came out since: that one, and the screens say so.
            if (latest.version != version) found(latest, notify = false)
            if (!fetch(latest) { _check.value = UpdateCheck.Downloading(latest.version, it) }) {
                _check.value = UpdateCheck.Failed
                return@launch
            }
            _check.value = UpdateCheck.Installing(latest.version)
            withContext(Dispatchers.Main) { openInstaller(activity) }
        }
    }

    override fun checkNow() {
        if (running?.isActive == true) return
        running = scope.launch {
            _check.value = UpdateCheck.Looking
            val latest = runCatching { latest() }.getOrNull()
            if (latest == null) {
                _check.value = UpdateCheck.Failed
                return@launch
            }
            prefs.edit().putLong(KEY_CHECKED, System.currentTimeMillis()).apply()
            if (!Versions.isNewer(latest.version, BuildConfig.VERSION_NAME)) {
                _check.value = UpdateCheck.UpToDate(BuildConfig.VERSION_NAME)
                return@launch
            }
            found(latest, notify = false)
            _check.value = UpdateCheck.Idle
        }
    }

    /** The check job: the latest release, told about if newer. */
    fun check() {
        if (!enabled || !(mobileData || isUnmetered(context))) return
        val latest = runCatching { latest() }.getOrNull() ?: return
        prefs.edit().putLong(KEY_CHECKED, System.currentTimeMillis()).apply()
        if (Versions.isNewer(latest.version, BuildConfig.VERSION_NAME)) found(latest, notify = true)
    }

    /** A newer version is out: the app asks when open; with [notify], a notification once per version. */
    private fun found(latest: Release, notify: Boolean) {
        release = latest
        prefs.edit().putString(KEY_FOUND, latest.version).putString(KEY_CHANGES, latest.changes.joinToString("\n")).apply()
        _newVersion.value = NewVersion(latest.version, latest.changes)
        if (notify && prefs.getString(KEY_NOTIFIED, null) != latest.version) {
            prefs.edit().putString(KEY_NOTIFIED, latest.version).apply()
            notifyNew(latest.version)
        }
    }

    private fun notifyNew(version: String) {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, context.getString(R.string.update_channel), NotificationManager.IMPORTANCE_DEFAULT),
            )
        }
        val openApp = context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
            PendingIntent.getActivity(context, 0, it, PendingIntent.FLAG_IMMUTABLE)
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_sentrek_monochrome)
            .setContentTitle(context.getString(R.string.update_new_title, version))
            .setContentText(context.getString(R.string.update_new_notification))
            .setContentIntent(openApp)
            .setAutoCancel(true)
            .build()
        runCatching { manager.notify(NOTIFICATION_ID, notification) }
    }

    /**
     * [release]'s APK for this phone, downloaded and checked unless it's here already; false if
     * it couldn't be.
     */
    private fun fetch(release: Release, progress: (Float) -> Unit = {}): Boolean = synchronized(fetching) {
        val version = release.version
        if (ready == version) return true
        val asset = Versions.assetName(version, installedCode())?.let(release.assets::get) ?: return false
        if (!download(asset, progress)) return false
        if (!fits(apk)) {
            // Not this app, not newer, or signed by someone else: never installed.
            apk.delete()
            return false
        }
        prefs.edit().putString(KEY_READY, version).apply()
        return true
    }

    /**
     * Android's installer, with the downloaded file: it asks to confirm, installs, and offers
     * to open the app again (installing closes it). Where there's no such screen, a session
     * install, which asks too.
     */
    private fun openInstaller(activity: Activity) {
        val file = apk.takeIf { it.exists() } ?: return
        val uri = runCatching { FileProvider.getUriForFile(context, "${context.packageName}.update", file) }.getOrElse {
            _check.value = UpdateCheck.Failed
            return
        }
        @Suppress("DEPRECATION")
        val intent = Intent(Intent.ACTION_INSTALL_PACKAGE)
            .setDataAndType(uri, APK_TYPE)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
            .putExtra(Intent.EXTRA_INSTALLER_PACKAGE_NAME, context.packageName)
        val opened = runCatching { activity.startActivity(intent) }.isSuccess
        if (!opened) scope.launch { commit() }
    }

    /** What Android says about a session install, from [InstallResultReceiver]. */
    fun onResult(intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java) ?: return
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { context.startActivity(confirm) }
            }
            PackageInstaller.STATUS_SUCCESS -> Unit
            PackageInstaller.STATUS_FAILURE_ABORTED -> _check.value = UpdateCheck.Idle
            else -> _check.value = UpdateCheck.Failed
        }
    }

    private fun hiking(): Boolean = recorder.get().state.value != null || follow.get().active

    /** The update is in: its file and the notice go. */
    private fun forgetInstalled() {
        val version = prefs.getString(KEY_READY, null)
        if (version != null && (!apk.exists() || !Versions.isNewer(version, BuildConfig.VERSION_NAME))) {
            apk.delete()
            prefs.edit().remove(KEY_READY).apply()
        }
        val found = prefs.getString(KEY_FOUND, null)
        if (found != null && !Versions.isNewer(found, BuildConfig.VERSION_NAME)) prefs.edit().remove(KEY_FOUND).apply()
    }

    private fun scheduleCheck() {
        if (!enabled || System.currentTimeMillis() - prefs.getLong(KEY_CHECKED, 0) < CHECK_EVERY_MS) return
        val job = JobInfo.Builder(JOB_CHECK, ComponentName(context, UpdateJobService::class.java))
            .setRequiredNetworkType(if (mobileData) JobInfo.NETWORK_TYPE_ANY else JobInfo.NETWORK_TYPE_UNMETERED)
            .build()
        runCatching { jobs.schedule(job) }
    }

    private class Asset(val url: String, val size: Long)

    private class Release(val version: String, val assets: Map<String, Asset>, val changes: List<String>)

    private fun request(url: String) = Request.Builder()
        .url(url)
        .header("User-Agent", "Sentrek/${BuildConfig.VERSION_NAME}")
        .build()

    private fun latest(): Release? {
        val call = http.newCall(request(BuildConfig.RELEASES_URL).newBuilder().header("Accept", "application/vnd.github+json").build())
        call.execute().use { response ->
            if (!response.isSuccessful) return null
            val json = JSONObject(response.body?.string() ?: return null)
            if (json.optBoolean("draft") || json.optBoolean("prerelease")) return null
            val list = json.optJSONArray("assets") ?: return null
            val assets = (0 until list.length()).map(list::getJSONObject).associate {
                it.getString("name") to Asset(it.getString("browser_download_url"), it.optLong("size"))
            }
            return Release(json.getString("tag_name").trim().removePrefix("v"), assets, Versions.changes(json.optString("body")))
        }
    }

    private fun download(asset: Asset, progress: (Float) -> Unit): Boolean {
        prefs.edit().remove(KEY_READY).apply()
        apk.delete()
        apk.parentFile?.mkdirs()
        val part = File(apk.path + ".part")
        val done = runCatching {
            http.newCall(request(asset.url)).execute().use { response ->
                val body = response.body
                if (!response.isSuccessful || body == null) return@runCatching false
                val total = asset.size.takeIf { it > 0 } ?: body.contentLength()
                var read = 0L
                var told = -1
                part.outputStream().use { out ->
                    body.byteStream().use { input ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            out.write(buffer, 0, n)
                            read += n
                            // Once per percent: not a flood of updates to the screen.
                            val percent = if (total > 0) (read * 100 / total).toInt() else 0
                            if (percent != told) {
                                told = percent
                                progress(percent / 100f)
                            }
                        }
                    }
                }
            }
            (asset.size <= 0 || part.length() == asset.size) && part.renameTo(apk)
        }.getOrDefault(false)
        if (!done) part.delete()
        return done
    }

    /** The same app, newer, signed with the same key: what Android would accept anyway, checked first. */
    private fun fits(file: File): Boolean {
        val archive = context.packageManager.info(file.path, archive = true) ?: return false
        if (archive.packageName != context.packageName) return false
        if (PackageInfoCompat.getLongVersionCode(archive) <= installedCode()) return false
        // Some Androids don't read the signature from a file: the install checks it then.
        val theirs = signers(archive) ?: return true
        val ours = context.packageManager.info(context.packageName, archive = false)?.let(::signers) ?: return true
        return theirs == ours
    }

    private fun installedCode(): Long =
        context.packageManager.info(context.packageName, archive = false)?.let(PackageInfoCompat::getLongVersionCode) ?: 0

    private fun commit() {
        val file = apk.takeIf { it.exists() } ?: return
        runCatching {
            val installer = context.packageManager.packageInstaller
            // An earlier one still waiting for a yes, or cut short: this one replaces it.
            installer.mySessions.forEach { runCatching { installer.abandonSession(it.sessionId) } }
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                setAppPackageName(context.packageName)
                setSize(file.length())
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
                }
            }
            val id = installer.createSession(params)
            installer.openSession(id).use { session ->
                session.openWrite("base.apk", 0, file.length()).use { out ->
                    file.inputStream().use { it.copyTo(out) }
                    session.fsync(out)
                }
                // Mutable: Android adds the result to it.
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
                val result = PendingIntent.getBroadcast(context, id, Intent(context, InstallResultReceiver::class.java), flags)
                session.commit(result.intentSender)
            }
        }
    }

    private inner class Callbacks : Application.ActivityLifecycleCallbacks {
        override fun onActivityResumed(activity: Activity) {
            if (askedPermission) {
                askedPermission = false
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && context.packageManager.canRequestPackageInstalls()) update(activity)
            }
            // Back from the installer without installing: nothing is going on any more.
            if (_check.value is UpdateCheck.Installing) _check.value = UpdateCheck.Idle
        }

        override fun onActivityStopped(activity: Activity) {
            if (enabled) scheduleCheck()
        }

        override fun onActivityStarted(activity: Activity) {}
        override fun onActivityPaused(activity: Activity) {}
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
        override fun onActivityDestroyed(activity: Activity) {}
    }

    companion object {
        const val JOB_CHECK = 7101

        /** Earlier versions' background install. */
        private const val OLD_JOB_INSTALL = 7102

        private const val KEY_CHECKED = "checked"
        private const val KEY_READY = "ready"
        private const val KEY_FOUND = "found"
        private const val KEY_NOTIFIED = "notified"
        private const val KEY_CHANGES = "changes"

        private const val CHANNEL_ID = "updates"
        private const val NOTIFICATION_ID = 7

        private const val APK_TYPE = "application/vnd.android.package-archive"
        private const val CHECK_EVERY_MS = 12 * 60 * 60 * 1000L
    }
}

@Suppress("DEPRECATION")
private val SIGNATURE_FLAG =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES

/** An installed package's info, or an APK file's with [archive]; with its signatures. */
@Suppress("DEPRECATION")
private fun PackageManager.info(name: String, archive: Boolean): PackageInfo? = runCatching {
    when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> {
            val flags = PackageManager.PackageInfoFlags.of(SIGNATURE_FLAG.toLong())
            if (archive) getPackageArchiveInfo(name, flags) else getPackageInfo(name, flags)
        }
        archive -> getPackageArchiveInfo(name, SIGNATURE_FLAG)
        else -> getPackageInfo(name, SIGNATURE_FLAG)
    }
}.getOrNull()

@Suppress("DEPRECATION")
private fun signers(info: PackageInfo): Set<String>? {
    val list = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.signingInfo?.apkContentsSigners else info.signatures
    return list?.takeIf { it.isNotEmpty() }?.map { it.toCharsString() }?.toSet()
}
