package app.sentrek.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
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

    /** Light (the default), dark, OLED black, or the phone's own choice. */
    val theme: Flow<AppTheme> =
        context.settings.data.map { prefs ->
            AppTheme.entries.firstOrNull { it.name == prefs[THEME] } ?: AppTheme.Light
        }

    suspend fun setTheme(theme: AppTheme) {
        context.settings.edit { it[THEME] = theme.name }
    }

    /** Day or night map colours, on their own or following the app theme. */
    val mapTheme: Flow<MapTheme> =
        context.settings.data.map { prefs ->
            MapTheme.entries.firstOrNull { it.name == prefs[MAP_THEME] } ?: MapTheme.Day
        }

    suspend fun setMapTheme(theme: MapTheme) {
        context.settings.edit { it[MAP_THEME] = theme.name }
    }

    /**
     * Hides the status and navigation bars: more map, and no clock or battery icon burning
     * into an OLED screen. A swipe from the edge shows them for a moment.
     */
    val fullScreen: Flow<Boolean> =
        context.settings.data.map { it[FULL_SCREEN] ?: true }

    suspend fun setFullScreen(on: Boolean) {
        context.settings.edit { it[FULL_SCREEN] = on }
    }

    /** Map buttons and bar fade out after a while without touches (OLED burn-in). */
    val autoHideControls: Flow<Boolean> =
        context.settings.data.map { it[AUTO_HIDE_CONTROLS] ?: false }

    suspend fun setAutoHideControls(on: Boolean) {
        context.settings.edit { it[AUTO_HIDE_CONTROLS] = on }
    }

    /** Where the map was last left, so the app reopens there (also after a crash). */
    val lastCamera: Flow<SavedCamera?> =
        context.settings.data.map { prefs ->
            val lat = prefs[CAMERA_LAT] ?: return@map null
            val lon = prefs[CAMERA_LON] ?: return@map null
            SavedCamera(lat, lon, prefs[CAMERA_ZOOM] ?: SavedCamera.DEFAULT_ZOOM, prefs[CAMERA_BEARING] ?: 0.0)
                .takeIf { it.isValid }
        }

    suspend fun setLastCamera(camera: SavedCamera) {
        if (!camera.isValid) return
        context.settings.edit {
            it[CAMERA_LAT] = camera.lat
            it[CAMERA_LON] = camera.lon
            it[CAMERA_ZOOM] = camera.zoom
            it[CAMERA_BEARING] = camera.bearing
        }
    }

    private companion object {
        val SHOW_TRAIL_DIFFICULTY = booleanPreferencesKey("show_trail_difficulty")
        val GLOVE_MODE = booleanPreferencesKey("glove_mode")
        val OFF_ROUTE_VIBRATION = booleanPreferencesKey("off_route_vibration")
        val FOLLOW_PROFILE = booleanPreferencesKey("follow_profile")
        val THEME = stringPreferencesKey("theme")
        val MAP_THEME = stringPreferencesKey("map_theme")
        val FULL_SCREEN = booleanPreferencesKey("full_screen")
        val AUTO_HIDE_CONTROLS = booleanPreferencesKey("auto_hide_controls")
        val CAMERA_LAT = doublePreferencesKey("camera_lat")
        val CAMERA_LON = doublePreferencesKey("camera_lon")
        val CAMERA_ZOOM = doublePreferencesKey("camera_zoom")
        val CAMERA_BEARING = doublePreferencesKey("camera_bearing")
    }
}

/** The app's colours. [Black] is dark with pure black backgrounds: OLED screens switch those pixels off. */
enum class AppTheme { System, Light, Dark, Black }

/** Map colours. [Auto]: night map with a dark app theme, day map otherwise. */
enum class MapTheme { Auto, Day, Night }

/** A map view: centre, zoom and rotation. */
data class SavedCamera(val lat: Double, val lon: Double, val zoom: Double, val bearing: Double) {
    val isValid: Boolean
        get() = lat in -90.0..90.0 && lon in -180.0..180.0 && zoom in 0.0..24.0 && !bearing.isNaN()

    companion object {
        const val DEFAULT_ZOOM = 11.0
    }
}
