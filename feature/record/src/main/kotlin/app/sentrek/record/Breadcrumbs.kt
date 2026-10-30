package app.sentrek.record

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.Location
import android.os.BatteryManager
import android.os.Build
import android.telephony.SmsManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.sentrek.feature.record.R
import app.sentrek.ui.Format
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/**
 * Breadcrumbs: while recording, the position goes by SMS to the emergency contact every 30 or
 * 60 minutes, like the tracking of a satellite messenger but over the phone network. One text
 * when the recording starts, one each period, one when it ends. A text that doesn't go (no
 * signal, often, in the mountains) is tried again every couple of minutes with the newest
 * position. Texts cost what the user's plan charges for them: the settings say so. When the
 * battery runs low, one more text says so, with the position: the last one may come from there.
 *
 * Driven by [TrackingService]: [check] with each fix and every minute. While texts go out, a
 * quiet notification and a line on the map ([status]) say so.
 */
class Breadcrumbs(private val context: Context) {
    private val saved = context.getSharedPreferences("breadcrumbs", Context.MODE_PRIVATE)

    /** The text on its way, until the phone says whether it went. */
    private var pending: Pending? = null
    private var retryAt = 0L

    private class Pending(val session: Long, val sentAt: Long, val parts: Int, val lowBattery: Boolean) {
        var results = 0
        var failed = false
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val p = pending ?: return
            p.results++
            if (resultCode != android.app.Activity.RESULT_OK) p.failed = true
            if (p.results < p.parts) return
            pending = null
            if (p.failed) {
                Log.w(TAG, "Breadcrumb not sent (code $resultCode), trying again")
                retryAt = System.currentTimeMillis() + RETRY_MS
                _status.value = _status.value?.copy(noSignal = true)
            } else {
                saved.edit().putLong(SESSION, p.session).putLong(LAST_SENT, p.sentAt)
                    .apply { if (p.lowBattery) putLong(LOW_BATTERY_SENT, p.session) }
                    .apply()
                _status.value = _status.value?.copy(lastSent = p.sentAt, noSignal = false)
            }
            notifyStatus()
        }
    }

    fun start() {
        ContextCompat.registerReceiver(context, receiver, IntentFilter(ACTION_SENT), ContextCompat.RECEIVER_NOT_EXPORTED)
        // One left behind by a process that died.
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }

    fun stop() {
        runCatching { context.unregisterReceiver(receiver) }
        setStatus(null)
    }

    /**
     * Sends a text when one is due: [recording] going on (null: just ended), [fix] the latest
     * position, every [minutes] (0: off) to [to], called [name] in the contacts; with
     * [lowBattery], one more when the battery runs low.
     */
    fun check(recording: Recording?, fix: Location?, minutes: Int, to: String, name: String, lowBattery: Boolean) {
        val now = System.currentTimeMillis()
        val session = saved.getLong(SESSION, 0)
        val on = minutes > 0 && to.isNotBlank() && canSend()
        if (recording == null) {
            setStatus(null)
            if (session == 0L) return
            // The last one, without retries: nothing keeps running after the recording.
            if (on && fix != null) send(to, finished(fix), session, now)
            saved.edit().remove(SESSION).remove(LAST_SENT).remove(LOW_BATTERY_SENT).apply()
            return
        }
        if (!on) return setStatus(null)
        val last = saved.getLong(LAST_SENT, 0).takeIf { session == recording.startTime && it > 0 }
        setStatus(Status(name.ifBlank { to }, minutes, last, noSignal = now < retryAt))
        if (fix == null || now < retryAt) return
        pending?.let { if (now - it.sentAt < PENDING_TIMEOUT_MS) return else pending = null }
        when {
            session != recording.startTime -> send(to, started(fix, minutes), recording.startTime, now)
            lowBattery && saved.getLong(LOW_BATTERY_SENT, 0) != session && battery()?.let { it <= LOW_BATTERY_PERCENT } == true ->
                send(to, lowBattery(fix, battery() ?: LOW_BATTERY_PERCENT), session, now, lowBattery = true)
            now - saved.getLong(LAST_SENT, 0) >= minutes * 60_000L -> send(to, position(fix), session, now)
        }
    }

    private fun canSend() =
        ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED &&
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY_MESSAGING)

    /** The battery level in percent, null while charging (a power bank) or unknown. */
    private fun battery(): Int? {
        val manager = context.getSystemService(BatteryManager::class.java) ?: return null
        if (manager.isCharging) return null
        return manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).takeIf { it in 0..100 }
    }

    private fun send(to: String, text: String, session: Long, now: Long, lowBattery: Boolean = false) {
        val sms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(SmsManager::class.java)
        } else {
            @Suppress("DEPRECATION")
            SmsManager.getDefault()
        } ?: return
        runCatching {
            val parts = sms.divideMessage(gsm(text))
            val sent = PendingIntent.getBroadcast(
                context, 0, Intent(ACTION_SENT).setPackage(context.packageName), PendingIntent.FLAG_IMMUTABLE,
            )
            pending = Pending(session, now, parts.size, lowBattery)
            sms.sendMultipartTextMessage(to, null, parts, ArrayList(List(parts.size) { sent }), null)
        }.onFailure {
            Log.w(TAG, "Cannot send breadcrumb", it)
            pending = null
            retryAt = now + RETRY_MS
        }
    }

    private fun started(fix: Location, minutes: Int) =
        context.getString(R.string.breadcrumb_started, minutes, where(fix))

    private fun position(fix: Location) =
        context.getString(R.string.breadcrumb_position, time(fix.time), where(fix))

    private fun lowBattery(fix: Location, percent: Int) =
        context.getString(R.string.breadcrumb_low_battery, percent, time(fix.time), where(fix))

    private fun finished(fix: Location) =
        context.getString(R.string.breadcrumb_finished, where(fix))

    /** Coordinates, altitude and a map link: readable on any phone, without an app. */
    private fun where(fix: Location): String {
        val lat = String.format(Locale.ROOT, "%.5f", fix.latitude)
        val lon = String.format(Locale.ROOT, "%.5f", fix.longitude)
        val altitude = LocationRepository.altitude(fix)?.let { " " + Format.elevation(it) }.orEmpty()
        return "$lat,$lon$altitude https://osm.org/?mlat=$lat&mlon=$lon"
    }

    private fun setStatus(status: Status?) {
        if (status == _status.value) return
        _status.value = status
        notifyStatus()
    }

    /** A quiet notification while texts go out, gone with them. */
    private fun notifyStatus() {
        val manager = NotificationManagerCompat.from(context)
        val s = _status.value ?: return manager.cancel(NOTIFICATION_ID)
        if (!manager.areNotificationsEnabled()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, context.getString(R.string.breadcrumb_channel), NotificationManager.IMPORTANCE_MIN),
            )
        }
        val openApp = context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
            PendingIntent.getActivity(context, 0, it, PendingIntent.FLAG_IMMUTABLE)
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_record)
            .setContentTitle(context.getString(R.string.breadcrumb_notification, s.to, s.minutes))
            .setContentText(statusLine(context, s))
            .setContentIntent(openApp)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
        runCatching { manager.notify(NOTIFICATION_ID, notification) }
    }

    /** [to]: the contact's name, or the number. [lastSent]: null until the first text of this recording went. */
    data class Status(val to: String, val minutes: Int, val lastSent: Long?, val noSignal: Boolean)

    companion object {
        private val _status = MutableStateFlow<Status?>(null)

        /** While texts go out: whom to, how often, the last one. */
        val status: StateFlow<Status?> = _status.asStateFlow()

        /** "Last sent at 14:30", or why there's none yet. */
        fun statusLine(context: Context, s: Status): String = when {
            s.noSignal -> context.getString(R.string.breadcrumb_no_signal)
            s.lastSent == null -> context.getString(R.string.breadcrumb_none_yet)
            else -> context.getString(R.string.breadcrumb_last, time(s.lastSent))
        }

        private fun time(millis: Long) = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(millis))

        private const val TAG = "Breadcrumbs"
        private const val ACTION_SENT = "app.sentrek.record.BREADCRUMB_SENT"
        private const val CHANNEL_ID = "breadcrumbs"
        private const val NOTIFICATION_ID = 4
        private const val SESSION = "session"
        private const val LAST_SENT = "last_sent"
        private const val LOW_BATTERY_SENT = "low_battery_sent"

        /** Low enough that it may soon be off, high enough that the text still gets out. */
        private const val LOW_BATTERY_PERCENT = 15
        /**
         * Soon: without signal nothing leaves the phone, so a try costs nothing, and the
         * signal is often back within a few steps (over a ridge, out of a gorge).
         */
        private const val RETRY_MS = 30_000L

        /** The phone always answers; this only guards against it not doing so. */
        private const val PENDING_TIMEOUT_MS = 5 * 60_000L

        /**
         * Plain characters only: a single one outside the SMS alphabet (a narrow space in a
         * time, a "±") makes a text hold 70 characters instead of 160, and cost two or three.
         */
        private fun gsm(text: String) = buildString {
            for (c in text) append(if (c.code in 32..126 || c == '\n') c else ' ')
        }
    }
}
