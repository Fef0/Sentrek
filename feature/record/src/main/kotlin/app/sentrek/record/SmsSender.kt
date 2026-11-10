package app.sentrek.record

import android.Manifest
import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import android.util.Log
import androidx.core.content.ContextCompat
import java.text.Normalizer
import java.util.concurrent.atomic.AtomicInteger

/** Texts sent by the app itself, without the messaging app: the position texts and the SOS one. */
object SmsSender {
    private const val TAG = "SmsSender"
    private const val ACTION_SENT = "app.sentrek.record.SMS_SENT"
    private val next = AtomicInteger()

    /** Phones before Android 13 don't list texting on its own, only the phone network. */
    private val FEATURE = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        PackageManager.FEATURE_TELEPHONY_MESSAGING
    } else {
        PackageManager.FEATURE_TELEPHONY
    }

    /** The SMS alphabet; the last few take two places each. */
    private const val GSM = "@£\$¥èéùìòÇ\nØøÅåΔ_ΦΓΛΩΠΨΣΘΞÆæßÉ !\"#¤%&'()*+,-./0123456789:;<=>?¡" +
        "ABCDEFGHIJKLMNOPQRSTUVWXYZÄÖÑÜ§¿abcdefghijklmnopqrstuvwxyzäöñüà^{}\\[~]|€"

    private val LOOKALIKES = mapOf(
        '±' to "+/-", '′' to "'", '″' to "\"", '‘' to "'", '’' to "'", '“' to "\"", '”' to "\"",
        '–' to "-", '—' to "-", '…' to "...", '°' to " deg", '`' to "'",
    )

    /**
     * [text] in the SMS alphabet only. A single character outside it (a "±", a narrow space in
     * a time) makes a text hold 70 characters instead of 160 and cost two or three; worse,
     * Android then asks for phone details apps may no longer read, and the text can't go at all.
     */
    fun plain(text: String) = buildString {
        for (c in text) {
            when {
                c in GSM -> append(c)
                c.isWhitespace() -> append(' ')
                else -> LOOKALIKES[c]?.let(::append) ?: run {
                    // Accents the alphabet lacks (á, ê, ł...): the letter without them.
                    val bare = Normalizer.normalize(c.toString(), Normalizer.Form.NFD).filter { it in GSM }
                    append(bare.ifEmpty { "?" })
                }
            }
        }
    }

    fun hasPermission(context: Context) =
        ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED

    /** Allowed to, and the phone can text at all. */
    fun canSend(context: Context) = hasPermission(context) && context.packageManager.hasSystemFeature(FEATURE)

    /**
     * The SIM set for texts; with none set ("ask every time" on two SIMs) the one for calls or
     * data, since there may be nobody to ask (the phone in the pocket).
     */
    fun manager(context: Context): SmsManager? {
        val chosen = SubscriptionManager.getDefaultSmsSubscriptionId()
        val id = chosen.takeIf { it != SubscriptionManager.INVALID_SUBSCRIPTION_ID }
            ?: SubscriptionManager.getDefaultSubscriptionId().takeIf { it != SubscriptionManager.INVALID_SUBSCRIPTION_ID }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val sms = context.getSystemService(SmsManager::class.java) ?: return null
            if (id != null) sms.createForSubscriptionId(id) else sms
        } else {
            @Suppress("DEPRECATION")
            if (id != null) SmsManager.getSmsManagerForSubscriptionId(id) else SmsManager.getDefault()
        }
    }

    /**
     * Sends [text] to [to] and calls [onResult] once, on the main thread, when the phone says
     * whether every part went (false: no signal, mostly), or at once when it can't even try.
     */
    fun send(context: Context, to: String, text: String, onResult: (Boolean) -> Unit) {
        val app = context.applicationContext
        val sms = runCatching { manager(app) }.getOrNull()
        if (sms == null || !canSend(app)) return onResult(false)
        val id = next.incrementAndGet()
        val action = "$ACTION_SENT.$id"
        val parts = runCatching { sms.divideMessage(plain(text)) }.onFailure { Log.w(TAG, "Cannot split text", it) }.getOrNull()
            ?: return onResult(false)
        var results = 0
        var failed = false
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                results++
                if (resultCode != Activity.RESULT_OK) failed = true
                if (results < parts.size) return
                runCatching { app.unregisterReceiver(this) }
                if (failed) Log.w(TAG, "Text not sent (code $resultCode)")
                onResult(!failed)
            }
        }
        ContextCompat.registerReceiver(app, receiver, IntentFilter(action), ContextCompat.RECEIVER_NOT_EXPORTED)
        val sent = PendingIntent.getBroadcast(app, id, Intent(action).setPackage(app.packageName), PendingIntent.FLAG_IMMUTABLE)
        runCatching {
            sms.sendMultipartTextMessage(to, null, parts, ArrayList(List(parts.size) { sent }), null)
        }.onFailure {
            Log.w(TAG, "Cannot send text", it)
            runCatching { app.unregisterReceiver(receiver) }
            onResult(false)
        }
    }
}
