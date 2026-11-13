package app.sentrek.update

import android.app.Activity
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** F-Droid updates the app itself: nothing here. */
private object NoUpdater : AppUpdater {
    override val available = false
    override val newVersion: StateFlow<NewVersion?> = MutableStateFlow(null)
    override val check: StateFlow<UpdateCheck> = MutableStateFlow(UpdateCheck.Idle)
    override fun start() {}
    override fun update(activity: Activity, overMobileData: Boolean) {}
    override fun checkNow() {}
}

@Module
@InstallIn(SingletonComponent::class)
object UpdaterModule {
    @Provides
    fun updater(): AppUpdater = NoUpdater
}
