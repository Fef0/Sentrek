package app.sentrek.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.sentrek.geo.units.Units
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
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

    /**
     * For long trips: GPS fixes further apart, the map capped at a lower frame rate, no contour
     * lines, and black screens (OLED pixels that are black use no power).
     */
    val batterySaver: Flow<Boolean> =
        context.settings.data.map { it[BATTERY_SAVER] ?: false }

    suspend fun setBatterySaver(on: Boolean) {
        context.settings.edit { it[BATTERY_SAVER] = on }
    }

    /**
     * Survival situations only (Settings > Advanced): everything at the bare minimum. Turning
     * it on turns battery saver off, which it replaces.
     */
    val staminaMode: Flow<Boolean> =
        context.settings.data.map { it[STAMINA_MODE] ?: false }

    suspend fun setStaminaMode(on: Boolean) {
        context.settings.edit {
            it[STAMINA_MODE] = on
            if (on) it[BATTERY_SAVER] = false
        }
    }

    /** When the power modes come on by themselves (Settings > Advanced). */
    val autoPower: Flow<AutoPower> =
        context.settings.data.map {
            AutoPower(
                enabled = it[AUTO_POWER] ?: true,
                saverAt = it[AUTO_SAVER_AT] ?: AutoPower.DEFAULT_SAVER_AT,
                staminaAt = it[AUTO_STAMINA_AT] ?: AutoPower.DEFAULT_STAMINA_AT,
                saverArmed = it[AUTO_SAVER_ARMED] ?: true,
                staminaArmed = it[AUTO_STAMINA_ARMED] ?: true,
                phoneSaverSeen = it[PHONE_SAVER_SEEN] ?: false,
                saverByPhone = it[SAVER_BY_PHONE] ?: false,
            )
        }.distinctUntilChanged()

    suspend fun setAutoPower(on: Boolean) {
        context.settings.edit { it[AUTO_POWER] = on }
    }

    suspend fun setAutoLevel(stamina: Boolean, percent: Int) {
        context.settings.edit { it[if (stamina) AUTO_STAMINA_AT else AUTO_SAVER_AT] = percent }
    }

    /** Whether the mode may still come on by itself this time down (see PowerAutoSwitch). */
    suspend fun setAutoArmed(stamina: Boolean, armed: Boolean) {
        context.settings.edit { it[if (stamina) AUTO_STAMINA_ARMED else AUTO_SAVER_ARMED] = armed }
    }

    /** The phone's battery saver as last seen, and whether ours is on because of it. */
    suspend fun setPhoneSaver(seen: Boolean, saverByPhone: Boolean) {
        context.settings.edit {
            it[PHONE_SAVER_SEEN] = seen
            it[SAVER_BY_PHONE] = saverByPhone
        }
    }

    /** Which of normal, battery saver and stamina mode is on. */
    val powerMode: Flow<PowerMode> =
        context.settings.data.map {
            when {
                it[STAMINA_MODE] == true -> PowerMode.Stamina
                it[BATTERY_SAVER] == true -> PowerMode.Saver
                else -> PowerMode.Normal
            }
        }.distinctUntilChanged()

    /** GPS fix intervals (Settings > Advanced); the power mode is applied by the callers. */
    val gpsTiming: Flow<GpsTiming> =
        context.settings.data.map { prefs ->
            GpsTiming(
                screenMs = prefs[GPS_SCREEN_MS] ?: GpsTiming.Default.screenMs,
                followMs = prefs[GPS_FOLLOW_MS] ?: GpsTiming.Default.followMs,
                recordMs = prefs[GPS_RECORD_MS] ?: GpsTiming.Default.recordMs,
            )
        }

    suspend fun setGpsTiming(timing: GpsTiming) {
        context.settings.edit {
            it[GPS_SCREEN_MS] = timing.screenMs
            it[GPS_FOLLOW_MS] = timing.followMs
            it[GPS_RECORD_MS] = timing.recordMs
        }
    }

    /** Most frames per second the map draws; 0 for no cap (Settings > Advanced). */
    val mapMaxFps: Flow<Int> =
        context.settings.data.map { it[MAP_MAX_FPS] ?: 0 }

    suspend fun setMapMaxFps(fps: Int) {
        context.settings.edit { it[MAP_MAX_FPS] = fps }
    }

    /** Everything in Settings > Advanced back as it came. */
    suspend fun resetAdvanced() {
        context.settings.edit {
            it.remove(GPS_SCREEN_MS)
            it.remove(GPS_FOLLOW_MS)
            it.remove(GPS_RECORD_MS)
            it.remove(MAP_MAX_FPS)
            it.remove(AUTO_POWER)
            it.remove(AUTO_SAVER_AT)
            it.remove(AUTO_STAMINA_AT)
        }
    }

    /** The places ahead in the followed route's panel. */
    val followPlaces: Flow<Boolean> =
        context.settings.data.map { it[FOLLOW_PLACES] ?: true }

    suspend fun setFollowPlaces(show: Boolean) {
        context.settings.edit { it[FOLLOW_PLACES] = show }
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
            AppTheme.entries.firstOrNull { it.name == prefs[THEME] } ?: AppTheme.System
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
        context.settings.data.map { it[FULL_SCREEN] ?: false }

    suspend fun setFullScreen(on: Boolean) {
        context.settings.edit { it[FULL_SCREEN] = on }
    }

    /** Map buttons and bar fade out after a while without touches (OLED burn-in). */
    val autoHideControls: Flow<Boolean> =
        context.settings.data.map { it[AUTO_HIDE_CONTROLS] ?: false }

    suspend fun setAutoHideControls(on: Boolean) {
        context.settings.edit { it[AUTO_HIDE_CONTROLS] = on }
    }

    /** The map always points north: no turning with two fingers or in heading-up mode. */
    val rotationLocked: Flow<Boolean> =
        context.settings.data.map { it[ROTATION_LOCKED] ?: false }

    suspend fun setRotationLocked(locked: Boolean) {
        context.settings.edit { it[ROTATION_LOCKED] = locked }
    }

    /** Scale bar at the top left of the map. */
    val showScaleBar: Flow<Boolean> =
        context.settings.data.map { it[SHOW_SCALE_BAR] ?: true }

    suspend fun setShowScaleBar(show: Boolean) {
        context.settings.edit { it[SHOW_SCALE_BAR] = show }
    }

    /** Contour lines on the map, drawn from the terrain data. */
    val showContours: Flow<Boolean> =
        context.settings.data.map { it[SHOW_CONTOURS] ?: true }

    suspend fun setShowContours(show: Boolean) {
        context.settings.edit { it[SHOW_CONTOURS] = show }
    }

    /** Back on the map asks for a second press before leaving the app. */
    val confirmExit: Flow<Boolean> =
        context.settings.data.map { it[CONFIRM_EXIT] ?: true }

    suspend fun setConfirmExit(on: Boolean) {
        context.settings.edit { it[CONFIRM_EXIT] = on }
    }

    /** Units for each quantity; metric until chosen otherwise. */
    val units: Flow<Units> =
        context.settings.data.map { prefs ->
            val default = Units.Metric
            Units(
                distance = prefs[DISTANCE_UNIT].toEnum(default.distance),
                elevation = prefs[ELEVATION_UNIT].toEnum(default.elevation),
                speed = prefs[SPEED_UNIT].toEnum(default.speed),
                slope = prefs[SLOPE_UNIT].toEnum(default.slope),
                bearing = prefs[BEARING_UNIT].toEnum(default.bearing),
                coordinates = prefs[COORDINATE_FORMAT].toEnum(default.coordinates),
            )
        }

    suspend fun setUnits(units: Units) {
        context.settings.edit {
            it[DISTANCE_UNIT] = units.distance.name
            it[ELEVATION_UNIT] = units.elevation.name
            it[SPEED_UNIT] = units.speed.name
            it[SLOPE_UNIT] = units.slope.name
            it[BEARING_UNIT] = units.bearing.name
            it[COORDINATE_FORMAT] = units.coordinates.name
        }
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
        val FOLLOW_PLACES = booleanPreferencesKey("follow_places")
        val BATTERY_SAVER = booleanPreferencesKey("battery_saver")
        val STAMINA_MODE = booleanPreferencesKey("stamina_mode")
        val AUTO_POWER = booleanPreferencesKey("auto_power")
        val AUTO_SAVER_AT = intPreferencesKey("auto_saver_at")
        val AUTO_STAMINA_AT = intPreferencesKey("auto_stamina_at")
        val AUTO_SAVER_ARMED = booleanPreferencesKey("auto_saver_armed")
        val AUTO_STAMINA_ARMED = booleanPreferencesKey("auto_stamina_armed")
        val PHONE_SAVER_SEEN = booleanPreferencesKey("phone_saver_seen")
        val SAVER_BY_PHONE = booleanPreferencesKey("saver_by_phone")
        val GPS_SCREEN_MS = longPreferencesKey("gps_screen_ms")
        val GPS_FOLLOW_MS = longPreferencesKey("gps_follow_ms")
        val GPS_RECORD_MS = longPreferencesKey("gps_record_ms")
        val MAP_MAX_FPS = intPreferencesKey("map_max_fps")
        val THEME = stringPreferencesKey("theme")
        val MAP_THEME = stringPreferencesKey("map_theme")
        val FULL_SCREEN = booleanPreferencesKey("full_screen")
        val AUTO_HIDE_CONTROLS = booleanPreferencesKey("auto_hide_controls")
        val CONFIRM_EXIT = booleanPreferencesKey("confirm_exit")
        val SHOW_SCALE_BAR = booleanPreferencesKey("show_scale_bar")
        val SHOW_CONTOURS = booleanPreferencesKey("show_contours")
        val ROTATION_LOCKED = booleanPreferencesKey("rotation_locked")
        val DISTANCE_UNIT = stringPreferencesKey("distance_unit")
        val ELEVATION_UNIT = stringPreferencesKey("elevation_unit")
        val SPEED_UNIT = stringPreferencesKey("speed_unit")
        val SLOPE_UNIT = stringPreferencesKey("slope_unit")
        val BEARING_UNIT = stringPreferencesKey("bearing_unit")
        val COORDINATE_FORMAT = stringPreferencesKey("coordinate_format")
        val CAMERA_LAT = doublePreferencesKey("camera_lat")
        val CAMERA_LON = doublePreferencesKey("camera_lon")
        val CAMERA_ZOOM = doublePreferencesKey("camera_zoom")
        val CAMERA_BEARING = doublePreferencesKey("camera_bearing")
    }
}

/** The stored enum constant, or [default] when unset or no longer known. */
private inline fun <reified T : Enum<T>> String?.toEnum(default: T): T =
    enumValues<T>().firstOrNull { it.name == this } ?: default

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
