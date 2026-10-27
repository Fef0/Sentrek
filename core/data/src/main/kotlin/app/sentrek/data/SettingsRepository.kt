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

    /** Larger touch targets, wider long-press area, volume keys zoom the map. */
    val gloveMode: Flow<Boolean> =
        context.settings.data.map { it[GLOVE_MODE] ?: false }

    suspend fun setGloveMode(on: Boolean) {
        context.settings.edit { it[GLOVE_MODE] = on }
    }

    /** Vibrate when leaving the followed route and when back on it. */
    val offRouteVibration: Flow<Boolean> =
        context.settings.data.map { it[OFF_ROUTE_VIBRATION] ?: true }

    suspend fun setOffRouteVibration(on: Boolean) {
        context.settings.edit { it[OFF_ROUTE_VIBRATION] = on }
    }

    /** The elevation profile in the follow panel; hidden for more map. */
    val followProfile: Flow<Boolean> =
        context.settings.data.map { it[FOLLOW_PROFILE] ?: true }

    suspend fun setFollowProfile(show: Boolean) {
        context.settings.edit { it[FOLLOW_PROFILE] = show }
    }

    private companion object {
        val SHOW_TRAIL_DIFFICULTY = booleanPreferencesKey("show_trail_difficulty")
        val GLOVE_MODE = booleanPreferencesKey("glove_mode")
        val OFF_ROUTE_VIBRATION = booleanPreferencesKey("off_route_vibration")
        val FOLLOW_PROFILE = booleanPreferencesKey("follow_profile")
    }
}
