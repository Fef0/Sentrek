package app.sentrek

import android.app.Application
import app.sentrek.data.SettingsRepository
import app.sentrek.map.MapEngine
import app.sentrek.map.Server
import app.sentrek.map.ServerRoutes
import app.sentrek.ui.Format
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import javax.inject.Inject

@HiltAndroidApp
class SentrekApp : Application() {
    @Inject lateinit var settings: SettingsRepository
    @Inject lateinit var powerAutoSwitch: PowerAutoSwitch

    override fun onCreate() {
        super.onCreate()
        AppLanguage.applyToApp(this)
        MapEngine.init(this, BuildConfig.VERSION_NAME)
        // For the whole process: screens and the recording notification alike.
        MainScope().launch { settings.units.collect { Format.units = it } }
        // Before the first request goes out, so none reaches a server the user replaced.
        ServerRoutes.custom = routes(runBlocking { settings.servers.first() })
        MainScope().launch { settings.servers.collect { ServerRoutes.custom = routes(it) } }
        powerAutoSwitch.start(MainScope())
    }

    private fun routes(saved: Map<String, String>): Map<Server, String> = saved.mapNotNull { (name, address) ->
        Server.entries.firstOrNull { it.name == name }?.takeIf { Server.isValid(address) }?.let { it to address }
    }.toMap()
}
