package app.sentrek.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.settings by preferencesDataStore(name = "settings")

@Singleton
class SettingsRepository @Inject constructor(@ApplicationContext private val context: Context) {
    val showTrailDifficulty: Flow<Boolean> =
        context.settings.data.map { it[SHOW_TRAIL_DIFFICULTY] ?: true }

    suspend fun setShowTrailDifficulty(show: Boolean) {
        context.settings.edit { it[SHOW_TRAIL_DIFFICULTY] = show }
    }

    private companion object {
        val SHOW_TRAIL_DIFFICULTY = booleanPreferencesKey("show_trail_difficulty")
    }
}
