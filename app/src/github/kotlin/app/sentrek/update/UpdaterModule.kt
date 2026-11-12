package app.sentrek.update

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class UpdaterModule {
    @Binds
    abstract fun updater(updater: GitHubUpdater): AppUpdater
}
