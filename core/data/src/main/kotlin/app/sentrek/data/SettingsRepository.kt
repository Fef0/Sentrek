package app.sentrek.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
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

/** Works across Europe and on most phones elsewhere, even without a SIM. */
const val DEFAULT_EMERGENCY_NUMBER = "112"

@Singleton
class SettingsRepository @Inject constructor(@ApplicationContext private val context: Context) {
    /**
     * One setting as a flow. Any write sends all of them again: only real changes go on, or
     * every collector (the tracking service, the map) would redo its work for nothing.
     */
    private fun <T> prefs(read: (Preferences) -> T): Flow<T> = context.settings.data.map(read).distinctUntilChanged()

    val showTrailDifficulty: Flow<Boolean> =
        prefs { it[SHOW_TRAIL_DIFFICULTY] ?: true }

    suspend fun setShowTrailDifficulty(show: Boolean) {
        context.settings.edit { it[SHOW_TRAIL_DIFFICULTY] = show }
    }

    /**
     * Biggest buttons, wider long-press area, and the volume keys zoom whatever [volumeKeysZoom]
     * says. On for those who chose "Gloves" as button size in 0.14, when it was one.
     */
    val gloveMode: Flow<Boolean> =
        prefs { it[GLOVE_MODE] ?: (it[BUTTON_SIZE] == GLOVES_SIZE) }

    suspend fun setGloveMode(on: Boolean) {
        context.settings.edit {
            if (it[BUTTON_SIZE] == GLOVES_SIZE) it[BUTTON_SIZE] = ButtonSize.Largest.name
            it[GLOVE_MODE] = on
        }
    }

    /** The first-run guide was seen (or skipped); Settings › About can show it again. */
    val guideSeen: Flow<Boolean> =
        prefs { it[GUIDE_SEEN] ?: false }

    suspend fun setGuideSeen(seen: Boolean) {
        context.settings.edit { it[GUIDE_SEEN] = seen }
    }

    /** A buzz before each junction where a followed route turns. Off unless chosen. */
    val turnBuzz: Flow<Boolean> =
        prefs { it[TURN_BUZZ] ?: false }

    suspend fun setTurnBuzz(on: Boolean) {
        context.settings.edit { it[TURN_BUZZ] = on }
    }

    /** Volume keys zoom the map, without glove mode too. */
    val volumeKeysZoom: Flow<Boolean> =
        prefs { it[VOLUME_KEYS_ZOOM] ?: false }

    suspend fun setVolumeKeysZoom(on: Boolean) {
        context.settings.edit { it[VOLUME_KEYS_ZOOM] = on }
    }

    /** The + and - buttons on the map (without them, pinch, double-tap and volume keys zoom). Glove mode shows them anyway. */
    val zoomButtons: Flow<Boolean> =
        prefs { it[ZOOM_BUTTONS] ?: true }

    suspend fun setZoomButtons(show: Boolean) {
        context.settings.edit { it[ZOOM_BUTTONS] = show }
    }

    /** The SOS screen (Library > SOS) and everything on it. */
    val sosEnabled: Flow<Boolean> =
        prefs { it[SOS_ENABLED] ?: true }

    suspend fun setSosEnabled(on: Boolean) {
        context.settings.edit { it[SOS_ENABLED] = on }
    }

    /** Called from the emergency screen: 112 by default; 911, a mountain rescue, a friend… */
    val emergencyNumber: Flow<String> =
        prefs { it[EMERGENCY_NUMBER]?.takeIf(String::isNotBlank) ?: DEFAULT_EMERGENCY_NUMBER }

    suspend fun setEmergencyNumber(number: String) {
        context.settings.edit { if (number.isBlank()) it.remove(EMERGENCY_NUMBER) else it[EMERGENCY_NUMBER] = number.trim() }
    }

    /** Whom the emergency SMS goes to; empty: chosen each time. */
    val emergencySmsTo: Flow<String> =
        prefs { it[EMERGENCY_SMS_TO].orEmpty() }

    suspend fun setEmergencySmsTo(number: String) {
        context.settings.edit { if (number.isBlank()) it.remove(EMERGENCY_SMS_TO) else it[EMERGENCY_SMS_TO] = number.trim() }
    }

    /** The name of the contact the SMS goes to, as in the phone's contacts; empty: unknown. */
    val emergencyContactName: Flow<String> =
        prefs { it[EMERGENCY_CONTACT_NAME].orEmpty() }

    /** Picked from the phone's contacts; a blank [number] clears it. */
    suspend fun setEmergencyContact(name: String, number: String) {
        context.settings.edit {
            if (number.isBlank()) {
                it.remove(EMERGENCY_SMS_TO)
                it.remove(EMERGENCY_CONTACT_NAME)
            } else {
                it[EMERGENCY_SMS_TO] = number.trim()
                it[EMERGENCY_CONTACT_NAME] = name.trim()
            }
        }
    }

    /** While recording, the position goes by SMS to the emergency contact every so many minutes; 0: never. */
    val breadcrumbMinutes: Flow<Int> =
        prefs { it[BREADCRUMB_MINUTES] ?: 0 }

    suspend fun setBreadcrumbMinutes(minutes: Int) {
        context.settings.edit { it[BREADCRUMB_MINUTES] = minutes }
    }

    /** With breadcrumbs on: one more text when the battery runs low, before the phone goes off. */
    val lowBatterySms: Flow<Boolean> =
        prefs { it[LOW_BATTERY_SMS] ?: true }

    suspend fun setLowBatterySms(on: Boolean) {
        context.settings.edit { it[LOW_BATTERY_SMS] = on }
    }

    /** While following a route: the last water before a long dry stretch, on screen and with a buzz. */
    val waterAdvice: Flow<Boolean> =
        prefs { it[WATER_ADVICE] ?: true }

    suspend fun setWaterAdvice(on: Boolean) {
        context.settings.edit { it[WATER_ADVICE] = on }
    }

    /** While walking: rain, snow and storms on the way, from an hourly forecast (needs data). */
    val rainAdvice: Flow<Boolean> =
        prefs { it[RAIN_ADVICE] ?: true }

    suspend fun setRainAdvice(on: Boolean) {
        context.settings.edit { it[RAIN_ADVICE] = on }
    }

    /** While following a route: the long stretches in the sun ahead, from the terrain. */
    val shadeAdvice: Flow<Boolean> =
        prefs { it[SHADE_ADVICE] ?: true }

    suspend fun setShadeAdvice(on: Boolean) {
        context.settings.edit { it[SHADE_ADVICE] = on }
    }

    /** The turnaround alarm: off, before dark, or at a set time. */
    val turnaround: Flow<TurnaroundMode> =
        prefs { p -> TurnaroundMode.entries.firstOrNull { it.name == p[TURNAROUND] } ?: TurnaroundMode.Off }

    suspend fun setTurnaround(mode: TurnaroundMode) {
        context.settings.edit { it[TURNAROUND] = mode.name }
    }

    /** Before dark: the daylight to spare at the end of the route, in minutes. */
    val turnaroundMarginMinutes: Flow<Int> =
        prefs { it[TURNAROUND_MARGIN] ?: 30 }

    suspend fun setTurnaroundMarginMinutes(minutes: Int) {
        context.settings.edit { it[TURNAROUND_MARGIN] = minutes }
    }

    /** At a time: the time of day, in minutes from midnight. */
    val turnaroundTime: Flow<Int> =
        prefs { it[TURNAROUND_TIME] ?: (14 * 60) }

    suspend fun setTurnaroundTime(minuteOfDay: Int) {
        context.settings.edit { it[TURNAROUND_TIME] = minuteOfDay }
    }

    /** The user's own servers by name (see the map module's Server); missing: the default. */
    val servers: Flow<Map<String, String>> =
        prefs { p ->
            p.asMap().mapNotNull { (key, value) ->
                key.name.removePrefix(SERVER_PREFIX).takeIf { key.name.startsWith(SERVER_PREFIX) }?.let { it to value as String }
            }.toMap()
        }

    /** Blank: back to the default. */
    suspend fun setServer(name: String, address: String) {
        val key = stringPreferencesKey(SERVER_PREFIX + name)
        context.settings.edit { if (address.isBlank()) it.remove(key) else it[key] = address.trim() }
    }

    /** The SMS button on the emergency screen. */
    val emergencySms: Flow<Boolean> =
        prefs { it[EMERGENCY_SMS] ?: true }

    suspend fun setEmergencySms(on: Boolean) {
        context.settings.edit { it[EMERGENCY_SMS] = on }
    }

    val textSize: Flow<TextSize> =
        prefs { p -> p[TEXT_SIZE]?.let { runCatching { TextSize.valueOf(it) }.getOrNull() } ?: TextSize.Default }

    suspend fun setTextSize(size: TextSize) {
        context.settings.edit { it[TEXT_SIZE] = size.name }
    }

    /** 0.14's "Gloves" size reads as [ButtonSize.Largest], with [gloveMode] on. */
    val buttonSize: Flow<ButtonSize> =
        prefs { p ->
            when (val stored = p[BUTTON_SIZE]) {
                null -> ButtonSize.Standard
                GLOVES_SIZE -> ButtonSize.Largest
                else -> runCatching { ButtonSize.valueOf(stored) }.getOrDefault(ButtonSize.Standard)
            }
        }

    suspend fun setButtonSize(size: ButtonSize) {
        context.settings.edit {
            // Glove mode stays as it was: written out before the old size goes.
            if (it[BUTTON_SIZE] == GLOVES_SIZE && it[GLOVE_MODE] == null) it[GLOVE_MODE] = true
            it[BUTTON_SIZE] = size.name
        }
    }

    /** Vibrate when leaving the followed route and when back on it. */
    val offRouteVibration: Flow<Boolean> =
        prefs { it[OFF_ROUTE_VIBRATION] ?: true }

    suspend fun setOffRouteVibration(on: Boolean) {
        context.settings.edit { it[OFF_ROUTE_VIBRATION] = on }
    }

    /**
     * For long trips: GPS fixes further apart, the map capped at a lower frame rate and no
     * contour lines. The theme and map stay as the user chose them.
     */
    val batterySaver: Flow<Boolean> =
        prefs { it[BATTERY_SAVER] ?: false }

    suspend fun setBatterySaver(on: Boolean) {
        context.settings.edit { it[BATTERY_SAVER] = on }
    }

    /**
     * Survival situations only (Settings > Advanced): everything at the bare minimum. Turning
     * it on turns battery saver off, which it replaces.
     */
    val staminaMode: Flow<Boolean> =
        prefs { it[STAMINA_MODE] ?: false }

    suspend fun setStaminaMode(on: Boolean) {
        context.settings.edit {
            it[STAMINA_MODE] = on
            if (on) it[BATTERY_SAVER] = false
        }
    }

    /** When the power modes come on by themselves (Settings > Advanced). */
    val autoPower: Flow<AutoPower> =
        prefs {
            AutoPower(
                enabled = it[AUTO_POWER] ?: true,
                saverAt = it[AUTO_SAVER_AT] ?: AutoPower.DEFAULT_SAVER_AT,
                staminaAt = it[AUTO_STAMINA_AT] ?: AutoPower.DEFAULT_STAMINA_AT,
                saverArmed = it[AUTO_SAVER_ARMED] ?: true,
                staminaArmed = it[AUTO_STAMINA_ARMED] ?: true,
                phoneSaverSeen = it[PHONE_SAVER_SEEN] ?: false,
                saverByPhone = it[SAVER_BY_PHONE] ?: false,
            )
        }

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

    /** Normal, battery saver or stamina mode, as chosen in Settings. */
    suspend fun setPowerMode(mode: PowerMode) {
        context.settings.edit {
            it[STAMINA_MODE] = mode == PowerMode.Stamina
            it[BATTERY_SAVER] = mode == PowerMode.Saver
        }
    }

    /** Which of normal, battery saver and stamina mode is on. */
    val powerMode: Flow<PowerMode> =
        prefs {
            when {
                it[STAMINA_MODE] == true -> PowerMode.Stamina
                it[BATTERY_SAVER] == true -> PowerMode.Saver
                else -> PowerMode.Normal
            }
        }

    /** GPS fix intervals (Settings > Advanced); the power mode is applied by the callers. */
    val gpsTiming: Flow<GpsTiming> =
        prefs { prefs ->
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

    /**
     * Most frames per second the map draws; 0 for no cap (Settings > Advanced). Smart unless
     * chosen: as smooth as the screen allows under a finger, capped while nobody touches it.
     */
    val mapMaxFps: Flow<Int> =
        prefs { it[MAP_MAX_FPS] ?: PowerMode.SMART_FPS }

    suspend fun setMapMaxFps(fps: Int) {
        context.settings.edit { it[MAP_MAX_FPS] = fps }
    }

    /** Everything in Settings > Advanced back as it came. */
    /**
     * Every setting back to its default. Kept: where the map was left, and the automatic power
     * modes' memory of what they did (not choices of the user's). Tracks, places and maps are
     * files of their own, untouched.
     */
    suspend fun resetAll() {
        context.settings.edit { prefs ->
            for (key in prefs.asMap().keys - NOT_SETTINGS) prefs.remove(key)
        }
    }

    /** The trail card open: every note, the places ahead and the profile, not just the numbers. */
    val trailCardExpanded: Flow<Boolean> =
        prefs { it[TRAIL_CARD_EXPANDED] ?: false }

    suspend fun setTrailCardExpanded(expanded: Boolean) {
        context.settings.edit { it[TRAIL_CARD_EXPANDED] = expanded }
    }

    /** The whole list of places ahead open, rather than just the next one. */
    val followPlacesExpanded: Flow<Boolean> =
        prefs { it[FOLLOW_PLACES_EXPANDED] ?: false }

    suspend fun setFollowPlacesExpanded(expanded: Boolean) {
        context.settings.edit { it[FOLLOW_PLACES_EXPANDED] = expanded }
    }

    /** The walking totals on the Tracks screen: all time, or this year (the default). */
    val tripTotalsAllTime: Flow<Boolean> =
        prefs { it[TRIP_TOTALS_ALL_TIME] ?: false }

    suspend fun setTripTotalsAllTime(allTime: Boolean) {
        context.settings.edit { it[TRIP_TOTALS_ALL_TIME] = allTime }
    }

    /** Light (the default), dark, OLED black, or the phone's own choice. */
    val theme: Flow<AppTheme> =
        prefs { prefs ->
            AppTheme.entries.firstOrNull { it.name == prefs[THEME] } ?: AppTheme.System
        }

    suspend fun setTheme(theme: AppTheme) {
        context.settings.edit { it[THEME] = theme.name }
    }

    /** Day or night map colours, on their own or following the app theme. */
    val mapTheme: Flow<MapTheme> =
        prefs { prefs ->
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
        prefs { it[FULL_SCREEN] ?: false }

    suspend fun setFullScreen(on: Boolean) {
        context.settings.edit { it[FULL_SCREEN] = on }
    }

    /** Map buttons and bar fade out after a while without touches (OLED burn-in). */
    val autoHideControls: Flow<Boolean> =
        prefs { it[AUTO_HIDE_CONTROLS] ?: false }

    suspend fun setAutoHideControls(on: Boolean) {
        context.settings.edit { it[AUTO_HIDE_CONTROLS] = on }
    }

    /** The map always points north: no turning with two fingers or in heading-up mode. */
    val rotationLocked: Flow<Boolean> =
        prefs { it[ROTATION_LOCKED] ?: false }

    suspend fun setRotationLocked(locked: Boolean) {
        context.settings.edit { it[ROTATION_LOCKED] = locked }
    }

    /** Scale bar at the top left of the map. */
    /** The touch lock button on the map, against rain drops and pocket touches. */
    val showLockButton: Flow<Boolean> =
        prefs { it[SHOW_LOCK_BUTTON] ?: true }

    suspend fun setShowLockButton(show: Boolean) {
        context.settings.edit { it[SHOW_LOCK_BUTTON] = show }
    }

    val showScaleBar: Flow<Boolean> =
        prefs { it[SHOW_SCALE_BAR] ?: true }

    suspend fun setShowScaleBar(show: Boolean) {
        context.settings.edit { it[SHOW_SCALE_BAR] = show }
    }

    /** Contour lines on the map, drawn from the terrain data. */
    val showContours: Flow<Boolean> =
        prefs { it[SHOW_CONTOURS] ?: true }

    suspend fun setShowContours(show: Boolean) {
        context.settings.edit { it[SHOW_CONTOURS] = show }
    }

    /** Slopes of 30° and more shaded on the map, as on avalanche maps. */
    val showSlopes: Flow<Boolean> =
        prefs { it[SHOW_SLOPES] ?: false }

    suspend fun setShowSlopes(show: Boolean) {
        context.settings.edit { it[SHOW_SLOPES] = show }
    }

    /** Downloaded areas updated by themselves at startup, on Wi-Fi only. */
    val autoUpdateMaps: Flow<Boolean> =
        prefs { it[AUTO_UPDATE_MAPS] ?: false }

    suspend fun setAutoUpdateMaps(on: Boolean) {
        context.settings.edit { it[AUTO_UPDATE_MAPS] = on }
    }

    /** A reminder at startup when downloaded maps are a few months old. */
    val mapUpdateReminders: Flow<Boolean> =
        prefs { it[MAP_REMINDERS] ?: true }

    suspend fun setMapUpdateReminders(on: Boolean) {
        context.settings.edit { it[MAP_REMINDERS] = on }
    }

    /** When the user last put the reminder off (epoch ms); 0 if never. */
    val mapReminderSnoozedAt: Flow<Long> =
        prefs { it[MAP_REMINDER_SNOOZED] ?: 0L }

    suspend fun snoozeMapReminder(now: Long) {
        context.settings.edit { it[MAP_REMINDER_SNOOZED] = now }
    }

    /** Back on the map asks for a second press before leaving the app. */
    val confirmExit: Flow<Boolean> =
        prefs { it[CONFIRM_EXIT] ?: true }

    suspend fun setConfirmExit(on: Boolean) {
        context.settings.edit { it[CONFIRM_EXIT] = on }
    }

    /** Units for each quantity; metric until chosen otherwise. */
    val units: Flow<Units> =
        prefs { prefs ->
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
        prefs { prefs ->
            val lat = prefs[CAMERA_LAT] ?: return@prefs null
            val lon = prefs[CAMERA_LON] ?: return@prefs null
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
        val TURN_BUZZ = booleanPreferencesKey("turn_buzz")
        val GUIDE_SEEN = booleanPreferencesKey("guide_seen")

        /** Button size in 0.14, when glove mode was one: now glove mode on, Largest buttons. */
        private const val GLOVES_SIZE = "Gloves"
        val TEXT_SIZE = stringPreferencesKey("text_size")
        val BUTTON_SIZE = stringPreferencesKey("button_size")
        val VOLUME_KEYS_ZOOM = booleanPreferencesKey("volume_keys_zoom")
        val ZOOM_BUTTONS = booleanPreferencesKey("zoom_buttons")
        val EMERGENCY_SMS = booleanPreferencesKey("emergency_sms")
        val SOS_ENABLED = booleanPreferencesKey("sos_enabled")
        val EMERGENCY_NUMBER = stringPreferencesKey("emergency_number")
        val EMERGENCY_SMS_TO = stringPreferencesKey("emergency_sms_to")
        val EMERGENCY_CONTACT_NAME = stringPreferencesKey("emergency_contact_name")
        val BREADCRUMB_MINUTES = intPreferencesKey("breadcrumb_minutes")
        val LOW_BATTERY_SMS = booleanPreferencesKey("low_battery_sms")
        val TURNAROUND = stringPreferencesKey("turnaround")
        val WATER_ADVICE = booleanPreferencesKey("water_advice")
        val RAIN_ADVICE = booleanPreferencesKey("rain_advice")
        val SHADE_ADVICE = booleanPreferencesKey("shade_advice")
        val TURNAROUND_MARGIN = intPreferencesKey("turnaround_margin")
        val TURNAROUND_TIME = intPreferencesKey("turnaround_time")
        private const val SERVER_PREFIX = "server_"
        val OFF_ROUTE_VIBRATION = booleanPreferencesKey("off_route_vibration")
        val TRAIL_CARD_EXPANDED = booleanPreferencesKey("trail_card_expanded")
        val FOLLOW_PLACES_EXPANDED = booleanPreferencesKey("follow_places_expanded")
        val TRIP_TOTALS_ALL_TIME = booleanPreferencesKey("trip_totals_all_time")
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
        val SHOW_LOCK_BUTTON = booleanPreferencesKey("show_lock_button")
        val SHOW_CONTOURS = booleanPreferencesKey("show_contours")
        val SHOW_SLOPES = booleanPreferencesKey("show_slopes")
        val MAP_REMINDERS = booleanPreferencesKey("map_reminders")
        val AUTO_UPDATE_MAPS = booleanPreferencesKey("auto_update_maps")
        val MAP_REMINDER_SNOOZED = longPreferencesKey("map_reminder_snoozed")
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

        /** State kept in the same store that [resetAll] leaves alone. */
        private val NOT_SETTINGS = setOf(
            CAMERA_LAT, CAMERA_LON, CAMERA_ZOOM, CAMERA_BEARING,
            AUTO_SAVER_ARMED, AUTO_STAMINA_ARMED, PHONE_SAVER_SEEN, SAVER_BY_PHONE, MAP_REMINDER_SNOOZED,
        )
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

/** When the turnaround alarm rings. */
enum class TurnaroundMode { Off, BeforeDark, AtTime }
