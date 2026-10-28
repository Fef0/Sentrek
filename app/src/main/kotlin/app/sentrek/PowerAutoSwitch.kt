package app.sentrek

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.sentrek.data.AutoPower
import app.sentrek.data.PowerMode
import app.sentrek.data.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Turns battery saver, then stamina mode, on by itself as the battery runs down (Settings >
 * Advanced has the levels), and says so in a notification. Only on the way down and off the
 * charger: a mode turned off by hand stays off until the battery has been charged above its
 * level again. Battery saver also follows the phone's own: on with it, and off with it when
 * that's why it came on. Runs while the app does (open, recording or following): when it matters.
 */
@Singleton
class PowerAutoSwitch @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
) {
    private data class Battery(val percent: Int, val charging: Boolean, val phoneSaver: Boolean)

    fun start(scope: CoroutineScope) {
        scope.launch {
            combine(battery(), settings.autoPower, settings.powerMode) { b, auto, mode -> Triple(b, auto, mode) }
                .collect { (b, auto, mode) -> check(b, auto, mode) }
        }
    }

    private suspend fun check(battery: Battery, auto: AutoPower, mode: PowerMode) {
        if (!auto.enabled) return
        // The phone's battery saver: on its changes only, so a choice made by hand stands.
        if (battery.phoneSaver != auto.phoneSaverSeen) {
            when {
                battery.phoneSaver && mode == PowerMode.Normal -> {
                    settings.setPhoneSaver(seen = true, saverByPhone = true)
                    settings.setBatterySaver(true)
                    notify(R.string.auto_saver_title, context.getString(R.string.auto_saver_phone_text))
                    return
                }
                !battery.phoneSaver && auto.saverByPhone && mode == PowerMode.Saver -> settings.setBatterySaver(false)
            }
            settings.setPhoneSaver(seen = battery.phoneSaver, saverByPhone = false)
            return
        }
        // Turned off by hand meanwhile: no longer the phone's doing.
        if (auto.saverByPhone && mode != PowerMode.Saver) settings.setPhoneSaver(seen = auto.phoneSaverSeen, saverByPhone = false)
        val staminaLow = auto.staminaAt > 0 && !battery.charging && battery.percent <= auto.staminaAt
        val saverLow = auto.saverAt > 0 && !battery.charging && battery.percent <= auto.saverAt
        // Armed again once above the level (or charging), so the next time down counts.
        if (!staminaLow && !auto.staminaArmed) settings.setAutoArmed(stamina = true, armed = true)
        if (!saverLow && !auto.saverArmed) settings.setAutoArmed(stamina = false, armed = true)

        if (staminaLow && auto.staminaArmed) {
            settings.setAutoArmed(stamina = true, armed = false)
            if (mode != PowerMode.Stamina) {
                settings.setStaminaMode(true)
                notify(R.string.auto_stamina_title, context.getString(R.string.auto_stamina_text, battery.percent))
            }
        } else if (saverLow && auto.saverArmed) {
            settings.setAutoArmed(stamina = false, armed = false)
            // Stamina mode already does more.
            if (mode == PowerMode.Normal) {
                settings.setBatterySaver(true)
                notify(R.string.auto_saver_title, context.getString(R.string.auto_saver_text, battery.percent))
            }
        }
    }

    /** The battery level, whether it's charging and the phone's battery saver, as they change. */
    private fun battery(): Flow<Battery> = callbackFlow {
        val power = context.getSystemService(PowerManager::class.java)
        var last: Intent? = null
        fun send() {
            last?.let(::read)?.let { trySend(it.copy(phoneSaver = power?.isPowerSaveMode == true)) }
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == Intent.ACTION_BATTERY_CHANGED) last = intent
                send()
            }
        }
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED).apply { addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED) }
        // Sticky: the current battery state comes back at once.
        last = ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        send()
        awaitClose { context.unregisterReceiver(receiver) }
    }.distinctUntilChanged()

    private fun read(intent: Intent): Battery? {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) return null
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        return Battery(level * 100 / scale, charging, phoneSaver = false)
    }

    private fun notify(title: Int, text: String) {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, context.getString(R.string.auto_channel), NotificationManager.IMPORTANCE_DEFAULT),
            )
        }
        val openApp = context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
            PendingIntent.getActivity(context, 0, it, PendingIntent.FLAG_IMMUTABLE)
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_battery)
            .setContentTitle(context.getString(title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openApp)
            .setAutoCancel(true)
            .build()
        runCatching { manager.notify(NOTIFICATION_ID, notification) }
    }

    private companion object {
        const val CHANNEL_ID = "power"

        /** One at a time: stamina mode replaces the battery saver notice. */
        const val NOTIFICATION_ID = 3
    }
}
