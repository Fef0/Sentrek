package app.sentrek

import android.app.Application
import app.sentrek.data.SettingsRepository
import app.sentrek.map.MapEngine
import app.sentrek.ui.Format
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class SentrekApp : Application() {
    @Inject lateinit var settings: SettingsRepository

    override fun onCreate() {
        super.onCreate()
        MapEngine.init(this, BuildConfig.VERSION_NAME)
        // For the whole process: screens and the recording notification alike.
        MainScope().launch { settings.units.collect { Format.units = it } }
    }
}
