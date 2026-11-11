package app.sentrek

import android.app.Application
import app.sentrek.data.SettingsRepository
import app.sentrek.map.MapEngine
import app.sentrek.map.Server
import app.sentrek.map.ServerRoutes
import app.sentrek.ui.Format
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
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
        // Read in the background: requests wait for it (ServerRoutes), the first screen doesn't.
        MainScope().launch(Dispatchers.IO) { settings.servers.collect { ServerRoutes.custom = routes(it) } }
        powerAutoSwitch.start(MainScope())
    }

    private fun routes(saved: Map<String, String>): Map<Server, String> = saved.mapNotNull { (name, address) ->
        Server.entries.firstOrNull { it.name == name }?.takeIf { Server.isValid(address) }?.let { it to address }
    }.toMap()
}
