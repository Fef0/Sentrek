package app.sentrek.update

import android.app.Activity
import android.app.Application
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
import androidx.core.content.IntentCompat
import androidx.core.content.pm.PackageInfoCompat
import app.sentrek.BuildConfig
import app.sentrek.data.SettingsRepository
import app.sentrek.maps.RegionRepository
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
import java.lang.ref.WeakReference
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Checks the latest GitHub release now and then, downloads the APK for this phone's CPU (on
 * Wi-Fi, unless mobile data is allowed), and installs it over this one.
 *
 * It installs only with the app in the background for a few minutes, and never while recording,
 * following a route or downloading maps: installing closes the app. From Android 12, once the
 * user has allowed installs from Sentrek, that needs nobody; otherwise the app asks when opened.
 */
@Singleton
class GitHubUpdater @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
    // Lazy: made at app start, before the map engine these need is ready.
    private val recorder: Lazy<RecordingRepository>,
    private val follow: Lazy<FollowRepository>,
    private val regions: Lazy<RegionRepository>,
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

    /** The check the user asked for, while it runs. */
    private var manual: Job? = null
    private val fetching = Any()

    private val _waiting = MutableStateFlow<String?>(null)
    override val waiting: StateFlow<String?> = _waiting.asStateFlow()

    @Volatile private var enabled = false
    @Volatile private var mobileData = false

    /** Activities started: 0 with the app in the background. */
    @Volatile private var shown = 0

    /** The activity on screen, for the confirmation Android may ask for. */
    @Volatile private var resumed: WeakReference<Activity>? = null

    /** Android wanted a yes for this version after all: the app asks, even if it could install alone. */
    @Volatile private var needsYes: String? = null

    /** Sent to Android's "install unknown apps" page: installs once back, if allowed. */
    @Volatile private var askedPermission = false

    /** The version downloaded and checked, if its file is still there. */
    private val ready: String?
        get() = prefs.getString(KEY_READY, null)?.takeIf { apk.exists() }

    override fun start() {
        (context.applicationContext as Application).registerActivityLifecycleCallbacks(Callbacks())
        scope.launch {
            combine(settings.autoUpdateApp, settings.useMobileData, ::Pair).collect { (on, mobile) ->
                enabled = on
                mobileData = mobile
                if (on) {
                    forgetInstalled()
                    refreshWaiting()
                    scheduleCheck()
                } else {
                    turnOff()
                }
            }
        }
    }

    override fun install(activity: Activity) {
        val version = ready ?: return
        // Installing closes the app: not in the middle of a hike.
        if (hiking()) {
            _check.value = UpdateCheck.AfterHike(version)
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
            askedPermission = true
            val page = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            runCatching { activity.startActivity(page) }.onFailure { askedPermission = false }
            _check.value = UpdateCheck.Idle
            return
        }
        _waiting.value = null
        _check.value = UpdateCheck.Installing(version)
        scope.launch { commit(silent = canInstallAlone()) }
    }

    override fun checkNow(overMobileData: Boolean) {
        if (manual?.isActive == true) return
        manual = scope.launch {
            _check.value = UpdateCheck.Looking
            val release = runCatching { latest() }.getOrNull()
            if (release == null) {
                _check.value = UpdateCheck.Failed
                return@launch
            }
            prefs.edit().putLong(KEY_CHECKED, System.currentTimeMillis()).apply()
            val version = release.version
            if (!Versions.isNewer(version, BuildConfig.VERSION_NAME)) {
                _check.value = UpdateCheck.UpToDate(BuildConfig.VERSION_NAME)
                return@launch
            }
            if (ready != version && !overMobileData && !mobileData && !isUnmetered(context)) {
                _check.value = UpdateCheck.OnMobileData(version)
                return@launch
            }
            _check.value = UpdateCheck.Downloading(version, 0f)
            if (!fetch(release) { _check.value = UpdateCheck.Downloading(version, it) }) {
                _check.value = UpdateCheck.Failed
                return@launch
            }
            val activity = resumed?.get()
            if (activity == null) {
                _check.value = UpdateCheck.Idle
                refreshWaiting()
                return@launch
            }
            withContext(Dispatchers.Main) { install(activity) }
        }
    }

    /** The check job: the latest release, downloaded if newer and right for this phone. */
    fun check() {
        if (!enabled || !(mobileData || isUnmetered(context))) return
        val release = runCatching { latest() }.getOrNull() ?: return
        prefs.edit().putLong(KEY_CHECKED, System.currentTimeMillis()).apply()
        val version = release.version
        if (!Versions.isNewer(version, BuildConfig.VERSION_NAME) || version == prefs.getString(KEY_SKIP, null)) return
        if (!fetch(release)) return
        refreshWaiting()
        if (shown == 0 && canInstallAlone()) scheduleInstall(SOON_MS)
    }

    /**
     * [release]'s APK for this phone, downloaded and checked unless it's here already; false if
     * it couldn't be. One at a time: the check job and the button share the file.
     */
    private fun fetch(release: Release, progress: (Float) -> Unit = {}): Boolean = synchronized(fetching) {
        val version = release.version
        if (ready == version) return true
        val asset = Versions.assetName(version, installedCode())?.let(release.assets::get) ?: return false
        if (!download(asset, progress)) return false
        if (!fits(apk)) {
            // Not this app, not newer, or signed by someone else: never installed, not fetched again.
            apk.delete()
            prefs.edit().putString(KEY_SKIP, version).apply()
            return false
        }
        prefs.edit().putString(KEY_READY, version).apply()
        return true
    }

    /** The install job: only if the app is still out of sight and nothing would be cut short. */
    fun installInBackground() {
        if (!enabled || shown > 0 || ready == null || ready == needsYes || !canInstallAlone()) return
        if (busy()) return scheduleInstall(BUSY_RETRY_MS)
        commit(silent = true)
    }

    /** What Android says about an install, from [InstallResultReceiver]. */
    fun onResult(intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java) ?: return
                val activity = resumed?.get()
                if (activity != null) {
                    runCatching { activity.startActivity(confirm) }
                } else {
                    needsYes = ready
                    refreshWaiting()
                }
            }
            // The file can't go over this app: not tried again.
            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE,
            PackageInstaller.STATUS_FAILURE_CONFLICT,
            PackageInstaller.STATUS_FAILURE_INVALID -> {
                prefs.edit().putString(KEY_SKIP, ready).remove(KEY_READY).apply()
                apk.delete()
                _waiting.value = null
                _check.value = UpdateCheck.Failed
            }
            PackageInstaller.STATUS_SUCCESS -> Unit
            // Stopped by the phone (Play Protect, on an app it doesn't know, can only ask with the
            // app open), or a "no", or a full disk: the app asks first next time.
            else -> {
                needsYes = ready
                refreshWaiting()
                _check.value = UpdateCheck.Idle
            }
        }
    }

    private fun busy(): Boolean = hiking() || regions.get().status.value != null

    private fun hiking(): Boolean = recorder.get().state.value != null || follow.get().active

    /** From Android 12 an app can update itself without asking, once allowed to install apps. */
    private fun canInstallAlone(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && context.packageManager.canRequestPackageInstalls()

    private fun refreshWaiting() {
        _waiting.value = ready?.takeIf { !canInstallAlone() || it == needsYes }
    }

    /** The update is in: its file goes. */
    private fun forgetInstalled() {
        val version = prefs.getString(KEY_READY, null) ?: return
        if (!apk.exists() || !Versions.isNewer(version, BuildConfig.VERSION_NAME)) {
            apk.delete()
            prefs.edit().remove(KEY_READY).apply()
        }
    }

    private fun turnOff() {
        jobs.cancel(JOB_CHECK)
        jobs.cancel(JOB_INSTALL)
        apk.delete()
        prefs.edit().remove(KEY_READY).apply()
        _waiting.value = null
    }

    private fun scheduleCheck() {
        if (!enabled || System.currentTimeMillis() - prefs.getLong(KEY_CHECKED, 0) < CHECK_EVERY_MS) return
        val job = JobInfo.Builder(JOB_CHECK, ComponentName(context, UpdateJobService::class.java))
            .setRequiredNetworkType(if (mobileData) JobInfo.NETWORK_TYPE_ANY else JobInfo.NETWORK_TYPE_UNMETERED)
            .build()
        runCatching { jobs.schedule(job) }
    }

    private fun scheduleInstall(delayMs: Long) {
        val job = JobInfo.Builder(JOB_INSTALL, ComponentName(context, UpdateJobService::class.java))
            .setMinimumLatency(delayMs)
            .build()
        runCatching { jobs.schedule(job) }
    }

    private class Asset(val url: String, val size: Long)

    private class Release(val version: String, val assets: Map<String, Asset>)

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
            return Release(json.getString("tag_name").trim().removePrefix("v"), assets)
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

    private fun commit(silent: Boolean) {
        val file = apk.takeIf { it.exists() } ?: return
        runCatching {
            val installer = context.packageManager.packageInstaller
            // An earlier one still waiting for a yes, or cut short: this one replaces it.
            installer.mySessions.forEach { runCatching { installer.abandonSession(it.sessionId) } }
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                setAppPackageName(context.packageName)
                setSize(file.length())
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    setRequireUserAction(
                        if (silent) PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED
                        else PackageInstaller.SessionParams.USER_ACTION_UNSPECIFIED,
                    )
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
        override fun onActivityStarted(activity: Activity) {
            shown++
            jobs.cancel(JOB_INSTALL)
        }

        override fun onActivityResumed(activity: Activity) {
            resumed = WeakReference(activity)
            refreshWaiting()
            if (askedPermission) {
                askedPermission = false
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && context.packageManager.canRequestPackageInstalls()) install(activity)
            }
        }

        override fun onActivityPaused(activity: Activity) {
            if (resumed?.get() === activity) resumed = null
        }

        override fun onActivityStopped(activity: Activity) {
            shown = (shown - 1).coerceAtLeast(0)
            if (shown > 0 || !enabled) return
            scheduleCheck()
            // A few minutes out of sight first: not while the user is in a file picker or the camera.
            if (ready != null && canInstallAlone()) scheduleInstall(HIDDEN_MS)
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
        override fun onActivityDestroyed(activity: Activity) {}
    }

    companion object {
        const val JOB_CHECK = 7101
        const val JOB_INSTALL = 7102

        private const val KEY_CHECKED = "checked"
        private const val KEY_READY = "ready"
        private const val KEY_SKIP = "skip"

        private const val CHECK_EVERY_MS = 12 * 60 * 60 * 1000L
        private const val HIDDEN_MS = 5 * 60 * 1000L
        private const val SOON_MS = 60 * 1000L
        private const val BUSY_RETRY_MS = 30 * 60 * 1000L
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
