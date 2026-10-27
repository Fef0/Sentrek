package app.sentrek

import android.app.Application
import app.sentrek.map.MapEngine
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class SentrekApp : Application() {
    override fun onCreate() {
        super.onCreate()
        MapEngine.init(this, BuildConfig.VERSION_NAME)
    }
}
