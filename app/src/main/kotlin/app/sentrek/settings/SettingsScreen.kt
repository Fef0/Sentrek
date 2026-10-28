package app.sentrek.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.OutlinedButton
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.sentrek.BuildConfig
import app.sentrek.R
import app.sentrek.data.AppTheme
import app.sentrek.data.MapTheme
import app.sentrek.data.SettingsRepository
import app.sentrek.geo.units.BearingUnit
import app.sentrek.geo.units.CoordinateFormat
import app.sentrek.geo.units.DistanceUnit
import app.sentrek.geo.units.ElevationUnit
import app.sentrek.geo.units.SlopeUnit
import app.sentrek.geo.units.SpeedUnit
import app.sentrek.geo.units.UnitFormat
import app.sentrek.geo.units.Units
import app.sentrek.ui.Format
import app.sentrek.ui.TouchTarget
import app.sentrek.data.AutoPower
import app.sentrek.data.GpsTiming
import app.sentrek.data.PowerMode
import app.sentrek.ui.MapButton
import app.sentrek.ui.SentrekIcons
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(private val settings: SettingsRepository) : ViewModel() {
    val showTrailDifficulty: StateFlow<Boolean> =
        settings.showTrailDifficulty.stateIn(viewModelScope, SharingStarted.Eagerly, true)

    val gloveMode: StateFlow<Boolean> =
        settings.gloveMode.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun setShowTrailDifficulty(show: Boolean) {
        viewModelScope.launch { settings.setShowTrailDifficulty(show) }
    }

    val offRouteVibration: StateFlow<Boolean> =
        settings.offRouteVibration.stateIn(viewModelScope, SharingStarted.Eagerly, true)

    fun setGloveMode(on: Boolean) {
        viewModelScope.launch { settings.setGloveMode(on) }
    }

    fun setOffRouteVibration(on: Boolean) {
        viewModelScope.launch { settings.setOffRouteVibration(on) }
    }

    val theme: StateFlow<AppTheme> =
        settings.theme.stateIn(viewModelScope, SharingStarted.Eagerly, AppTheme.System)

    fun setTheme(theme: AppTheme) {
        viewModelScope.launch { settings.setTheme(theme) }
    }

    val mapTheme: StateFlow<MapTheme> =
        settings.mapTheme.stateIn(viewModelScope, SharingStarted.Eagerly, MapTheme.Day)

    fun setMapTheme(theme: MapTheme) {
        viewModelScope.launch { settings.setMapTheme(theme) }
    }

    val autoHideControls: StateFlow<Boolean> =
        settings.autoHideControls.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun setAutoHideControls(on: Boolean) {
        viewModelScope.launch { settings.setAutoHideControls(on) }
    }

    val fullScreen: StateFlow<Boolean> =
        settings.fullScreen.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun setFullScreen(on: Boolean) {
        viewModelScope.launch { settings.setFullScreen(on) }
    }

    val showScaleBar: StateFlow<Boolean> =
        settings.showScaleBar.stateIn(viewModelScope, SharingStarted.Eagerly, true)

    fun setShowScaleBar(show: Boolean) {
        viewModelScope.launch { settings.setShowScaleBar(show) }
    }

    val batterySaver: StateFlow<Boolean> =
        settings.batterySaver.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun setBatterySaver(on: Boolean) {
        viewModelScope.launch { settings.setBatterySaver(on) }
    }

    val staminaMode: StateFlow<Boolean> =
        settings.staminaMode.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun setStaminaMode(on: Boolean) {
        viewModelScope.launch { settings.setStaminaMode(on) }
    }

    val autoPower: StateFlow<AutoPower> =
        settings.autoPower.stateIn(viewModelScope, SharingStarted.Eagerly, AutoPower())

    fun setAutoPower(on: Boolean) {
        viewModelScope.launch { settings.setAutoPower(on) }
    }

    fun setAutoLevel(stamina: Boolean, percent: Int) {
        viewModelScope.launch { settings.setAutoLevel(stamina, percent) }
    }

    val gpsTiming: StateFlow<GpsTiming> =
        settings.gpsTiming.stateIn(viewModelScope, SharingStarted.Eagerly, GpsTiming.Default)

    fun setGpsTiming(change: (GpsTiming) -> GpsTiming) {
        viewModelScope.launch { settings.setGpsTiming(change(gpsTiming.value)) }
    }

    val mapMaxFps: StateFlow<Int> =
        settings.mapMaxFps.stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    fun setMapMaxFps(fps: Int) {
        viewModelScope.launch { settings.setMapMaxFps(fps) }
    }

    fun resetAdvanced() {
        viewModelScope.launch { settings.resetAdvanced() }
    }

    val showContours: StateFlow<Boolean> =
        settings.showContours.stateIn(viewModelScope, SharingStarted.Eagerly, true)

    fun setShowContours(show: Boolean) {
        viewModelScope.launch { settings.setShowContours(show) }
    }

    val rotationLocked: StateFlow<Boolean> =
        settings.rotationLocked.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun setRotationLocked(locked: Boolean) {
        viewModelScope.launch { settings.setRotationLocked(locked) }
    }

    val confirmExit: StateFlow<Boolean> =
        settings.confirmExit.stateIn(viewModelScope, SharingStarted.Eagerly, true)

    fun setConfirmExit(on: Boolean) {
        viewModelScope.launch { settings.setConfirmExit(on) }
    }

    val units: StateFlow<Units> =
        settings.units.stateIn(viewModelScope, SharingStarted.Eagerly, Format.units)

    fun setUnits(change: (Units) -> Units) {
        viewModelScope.launch { settings.setUnits(change(units.value)) }
    }
}

@Composable
fun SettingsScreen(onBack: () -> Unit, viewModel: SettingsViewModel = hiltViewModel()) {
    var advanced by rememberSaveable { mutableStateOf(false) }
    if (advanced) {
        BackHandler { advanced = false }
        return AdvancedSettings(viewModel, onBack = { advanced = false })
    }
    val showTrailDifficulty by viewModel.showTrailDifficulty.collectAsStateWithLifecycle()
    val gloveMode by viewModel.gloveMode.collectAsStateWithLifecycle()
    val offRouteVibration by viewModel.offRouteVibration.collectAsStateWithLifecycle()
    val theme by viewModel.theme.collectAsStateWithLifecycle()
    val mapTheme by viewModel.mapTheme.collectAsStateWithLifecycle()
    val fullScreen by viewModel.fullScreen.collectAsStateWithLifecycle()
    val autoHideControls by viewModel.autoHideControls.collectAsStateWithLifecycle()
    val confirmExit by viewModel.confirmExit.collectAsStateWithLifecycle()
    val showScaleBar by viewModel.showScaleBar.collectAsStateWithLifecycle()
    val showContours by viewModel.showContours.collectAsStateWithLifecycle()
    val batterySaver by viewModel.batterySaver.collectAsStateWithLifecycle()
    val staminaMode by viewModel.staminaMode.collectAsStateWithLifecycle()
    val rotationLocked by viewModel.rotationLocked.collectAsStateWithLifecycle()
    val units by viewModel.units.collectAsStateWithLifecycle()

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                MapButton(SentrekIcons.Back, stringResource(R.string.settings_back), onBack, Modifier.padding(vertical = 12.dp))
                Text(stringResource(R.string.bar_settings), style = MaterialTheme.typography.headlineMedium)
            }

            // First: on a long trip, the one switch that matters most.
            // What the power modes override is greyed out: it waits until they're off.
            val saving = batterySaver || staminaMode
            ToggleRow(
                icon = SentrekIcons.BatterySaver,
                title = stringResource(R.string.settings_battery_saver),
                hint = stringResource(if (staminaMode) R.string.settings_battery_saver_stamina else R.string.settings_battery_saver_hint),
                checked = batterySaver && !staminaMode,
                onChange = viewModel::setBatterySaver,
                // Stamina mode replaces it: off there.
                enabled = !staminaMode,
            )

            SectionTitle(stringResource(R.string.settings_appearance))
            ChoiceRow(
                enabled = !saving,
                title = stringResource(R.string.settings_app_theme),
                options = listOf(
                    AppTheme.System to stringResource(R.string.settings_theme_system),
                    AppTheme.Light to stringResource(R.string.settings_theme_light),
                    AppTheme.Dark to stringResource(R.string.settings_theme_dark),
                    AppTheme.Black to stringResource(R.string.settings_theme_black),
                ),
                selected = theme,
                onChange = viewModel::setTheme,
            )
            ChoiceRow(
                enabled = !saving,
                title = stringResource(R.string.settings_map_theme),
                hint = stringResource(R.string.settings_map_theme_hint),
                options = listOf(
                    MapTheme.Auto to stringResource(R.string.settings_map_theme_auto),
                    MapTheme.Day to stringResource(R.string.settings_map_theme_day),
                    MapTheme.Night to stringResource(R.string.settings_map_theme_night),
                ),
                selected = mapTheme,
                onChange = viewModel::setMapTheme,
            )
            ToggleRow(
                title = stringResource(R.string.settings_full_screen),
                hint = stringResource(R.string.settings_full_screen_hint),
                checked = fullScreen,
                onChange = viewModel::setFullScreen,
            )
            ToggleRow(
                title = stringResource(R.string.settings_auto_hide),
                hint = stringResource(R.string.settings_auto_hide_hint),
                checked = autoHideControls,
                onChange = viewModel::setAutoHideControls,
            )

            SectionTitle(stringResource(R.string.settings_units))
            // Metric or imperial sets distance, height and speed; the rest stays as chosen.
            val system = listOf(Units.Metric, Units.Imperial).firstOrNull {
                it.distance == units.distance && it.elevation == units.elevation && it.speed == units.speed
            }
            ChoiceRow(
                title = stringResource(R.string.settings_units_system),
                options = listOf(
                    Units.Metric to stringResource(R.string.settings_units_metric),
                    Units.Imperial to stringResource(R.string.settings_units_imperial),
                ),
                selected = system,
                onChange = { preset ->
                    viewModel.setUnits { it.copy(distance = preset.distance, elevation = preset.elevation, speed = preset.speed) }
                },
                inline = true,
            )
            ChoiceRow(
                title = stringResource(R.string.settings_units_coordinates),
                // Mont Blanc, as an example of the chosen format.
                hint = UnitFormat.coordinates(45.83265, 6.86517, units.coordinates),
                options = listOf(
                    CoordinateFormat.Decimal to "DD.ddd",
                    CoordinateFormat.DegreesMinutes to "DD MM.mmm",
                    CoordinateFormat.DegreesMinutesSeconds to "DD MM SS",
                ),
                selected = units.coordinates,
                onChange = { format -> viewModel.setUnits { it.copy(coordinates = format) } },
            )
            // A mix of units (say km with feet) opens the details straight away.
            var moreUnits by rememberSaveable { mutableStateOf(system == null) }
            ExpandRow(
                title = stringResource(R.string.settings_more_units),
                hint = stringResource(R.string.settings_more_units_hint),
                expanded = moreUnits,
                onToggle = { moreUnits = !moreUnits },
            )
            if (moreUnits) {
                ChoiceRow(
                    title = stringResource(R.string.settings_units_distance),
                    options = listOf(DistanceUnit.Kilometers to "km", DistanceUnit.Miles to "mi", DistanceUnit.NauticalMiles to "nmi"),
                    selected = units.distance,
                    onChange = { unit -> viewModel.setUnits { it.copy(distance = unit) } },
                    inline = true,
                )
                ChoiceRow(
                    title = stringResource(R.string.settings_units_elevation),
                    options = listOf(ElevationUnit.Meters to "m", ElevationUnit.Feet to "ft"),
                    selected = units.elevation,
                    onChange = { unit -> viewModel.setUnits { it.copy(elevation = unit) } },
                    inline = true,
                )
                ChoiceRow(
                    title = stringResource(R.string.settings_units_speed),
                    options = listOf(SpeedUnit.KilometersPerHour to "km/h", SpeedUnit.MilesPerHour to "mph", SpeedUnit.Knots to "kn"),
                    selected = units.speed,
                    onChange = { unit -> viewModel.setUnits { it.copy(speed = unit) } },
                    inline = true,
                )
                ChoiceRow(
                    title = stringResource(R.string.settings_units_slope),
                    options = listOf(SlopeUnit.Degrees to "12°", SlopeUnit.Percent to "21%"),
                    selected = units.slope,
                    onChange = { unit -> viewModel.setUnits { it.copy(slope = unit) } },
                    inline = true,
                )
                ChoiceRow(
                    title = stringResource(R.string.settings_units_bearing),
                    options = listOf(BearingUnit.Degrees to "245°", BearingUnit.Mils to "4356 mil"),
                    selected = units.bearing,
                    onChange = { unit -> viewModel.setUnits { it.copy(bearing = unit) } },
                    inline = true,
                )
            }

            SectionTitle(stringResource(R.string.settings_map))
            ToggleRow(
                title = stringResource(R.string.settings_scale_bar),
                checked = showScaleBar,
                onChange = viewModel::setShowScaleBar,
            )
            ToggleRow(
                enabled = !saving,
                title = stringResource(R.string.settings_contours),
                checked = showContours,
                onChange = viewModel::setShowContours,
            )
            ToggleRow(
                title = stringResource(R.string.settings_lock_north),
                hint = stringResource(R.string.settings_lock_north_hint),
                checked = rotationLocked,
                onChange = viewModel::setRotationLocked,
            )
            ToggleRow(
                title = stringResource(R.string.settings_trail_difficulty),
                hint = stringResource(R.string.settings_trail_difficulty_hint),
                checked = showTrailDifficulty,
                onChange = viewModel::setShowTrailDifficulty,
            )

            SectionTitle(stringResource(R.string.settings_controls))
            ToggleRow(
                title = stringResource(R.string.settings_glove_mode),
                hint = stringResource(R.string.settings_glove_mode_hint),
                checked = gloveMode,
                onChange = viewModel::setGloveMode,
            )
            ToggleRow(
                title = stringResource(R.string.settings_off_route_vibration),
                hint = stringResource(R.string.settings_off_route_vibration_hint),
                checked = offRouteVibration,
                onChange = viewModel::setOffRouteVibration,
            )
            ToggleRow(
                title = stringResource(R.string.settings_confirm_exit),
                hint = stringResource(R.string.settings_confirm_exit_hint),
                checked = confirmExit,
                onChange = viewModel::setConfirmExit,
            )

            NavRow(
                title = stringResource(R.string.settings_advanced),
                hint = stringResource(R.string.settings_advanced_hint),
                onClick = { advanced = true },
            )

            SectionTitle(stringResource(R.string.settings_about))
            Text(stringResource(R.string.settings_version, BuildConfig.VERSION_NAME), style = MaterialTheme.typography.bodyLarge)
            Text(stringResource(R.string.settings_osm_credit), style = MaterialTheme.typography.bodyMedium)
            var allCredits by rememberSaveable { mutableStateOf(false) }
            ExpandRow(
                title = stringResource(R.string.settings_all_credits),
                hint = null,
                expanded = allCredits,
                onToggle = { allCredits = !allCredits },
            )
            if (allCredits) {
                Text(
                    stringResource(R.string.settings_credits),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(bottom = 16.dp),
                )
            }
        }
    }
}

@Composable
private fun ToggleRow(
    title: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    hint: String? = null,
    enabled: Boolean = true,
    icon: ImageVector? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = TouchTarget)
            .toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
            .padding(vertical = 6.dp)
            .alpha(if (enabled) 1f else DISABLED_ALPHA),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (icon != null) Icon(icon, contentDescription = null)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (hint != null) Text(hint, style = MaterialTheme.typography.bodyMedium)
        }
        // The whole row toggles; the switch only shows the state.
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

/** Map frame rate and GPS timing: rarely changed, so on a page of their own. */
@Composable
private fun AdvancedSettings(viewModel: SettingsViewModel, onBack: () -> Unit) {
    val fps by viewModel.mapMaxFps.collectAsStateWithLifecycle()
    val gps by viewModel.gpsTiming.collectAsStateWithLifecycle()
    val stamina by viewModel.staminaMode.collectAsStateWithLifecycle()
    val auto by viewModel.autoPower.collectAsStateWithLifecycle()
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                MapButton(SentrekIcons.Back, stringResource(R.string.settings_back), onBack, Modifier.padding(vertical = 12.dp))
                Text(stringResource(R.string.settings_advanced), style = MaterialTheme.typography.headlineMedium)
            }

            SectionTitle(stringResource(R.string.settings_stamina_section))
            ToggleRow(
                icon = SentrekIcons.BatteryAlert,
                title = stringResource(R.string.settings_stamina),
                hint = stringResource(
                    R.string.settings_stamina_hint,
                    PowerMode.STAMINA_FPS,
                    seconds(GpsTiming.Stamina.screenMs), seconds(GpsTiming.Stamina.followMs), seconds(GpsTiming.Stamina.recordMs),
                ),
                checked = stamina,
                onChange = viewModel::setStaminaMode,
            )
            ToggleRow(
                title = stringResource(R.string.settings_auto_power),
                hint = stringResource(R.string.settings_auto_hint),
                checked = auto.enabled,
                onChange = viewModel::setAutoPower,
            )
            ChoiceRow(
                enabled = auto.enabled,
                title = stringResource(R.string.settings_auto_saver),
                options = AutoPower.SAVER_CHOICES.map { it to "$it%" },
                selected = auto.saverAt,
                onChange = { viewModel.setAutoLevel(stamina = false, percent = it) },
            )
            ChoiceRow(
                enabled = auto.enabled,
                title = stringResource(R.string.settings_auto_stamina),
                options = AutoPower.STAMINA_CHOICES.map { it to "$it%" },
                selected = auto.staminaAt,
                onChange = { viewModel.setAutoLevel(stamina = true, percent = it) },
            )
            if (stamina) {
                Text(
                    stringResource(R.string.settings_stamina_overrides),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            SectionTitle(stringResource(R.string.settings_map))
            ChoiceRow(
                enabled = !stamina,
                title = stringResource(R.string.settings_map_fps),
                options = PowerMode.FPS_CHOICES.map { it to if (it == 0) stringResource(R.string.settings_map_fps_no_cap) else "$it" },
                selected = fps,
                onChange = viewModel::setMapMaxFps,
                hint = stringResource(R.string.settings_map_fps_hint),
            )

            SectionTitle(stringResource(R.string.settings_gps))
            Text(stringResource(R.string.settings_gps_hint), style = MaterialTheme.typography.bodyMedium)
            ChoiceRow(
                enabled = !stamina,
                title = stringResource(R.string.settings_gps_screen),
                options = GpsTiming.SCREEN_CHOICES.map { it to seconds(it) },
                selected = gps.screenMs,
                onChange = { ms -> viewModel.setGpsTiming { it.copy(screenMs = ms) } },
            )
            ChoiceRow(
                enabled = !stamina,
                title = stringResource(R.string.settings_gps_follow),
                options = GpsTiming.FOLLOW_CHOICES.map { it to seconds(it) },
                selected = gps.followMs,
                onChange = { ms -> viewModel.setGpsTiming { it.copy(followMs = ms) } },
                hint = stringResource(R.string.settings_gps_follow_hint),
            )
            ChoiceRow(
                enabled = !stamina,
                title = stringResource(R.string.settings_gps_record),
                options = GpsTiming.RECORD_CHOICES.map { it to seconds(it) },
                selected = gps.recordMs,
                onChange = { ms -> viewModel.setGpsTiming { it.copy(recordMs = ms) } },
                hint = stringResource(R.string.settings_gps_record_hint),
            )
            Text(
                stringResource(
                    R.string.settings_gps_saver,
                    seconds(GpsTiming.Saver.screenMs), seconds(GpsTiming.Saver.followMs), seconds(GpsTiming.Saver.recordMs),
                ),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(vertical = 6.dp),
            )

            OutlinedButton(
                onClick = viewModel::resetAdvanced,
                enabled = !stamina,
                modifier = Modifier.fillMaxWidth().heightIn(min = TouchTarget).padding(vertical = 4.dp),
            ) { Text(stringResource(R.string.settings_advanced_reset)) }
            Spacer(Modifier.height(16.dp))
        }
    }
}

private fun seconds(ms: Long): String = "${ms / 1_000} s"

/** Material's look for disabled content. */
private const val DISABLED_ALPHA = 0.38f

/** A row that opens a page of its own. */
@Composable
private fun NavRow(title: String, hint: String?, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = TouchTarget)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (hint != null) Text(hint, style = MaterialTheme.typography.bodyMedium)
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
    }
}

/** A row that opens or closes the rows below it. */
@Composable
private fun ExpandRow(title: String, hint: String?, expanded: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = TouchTarget)
            .clickable(role = Role.Button, onClick = onToggle)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (hint != null) Text(hint, style = MaterialTheme.typography.bodyMedium)
        }
        Icon(if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown, contentDescription = null)
    }
}

/**
 * Large segments, one per option: under the [title], or beside it when [inline] (for a few
 * short options). [selected] may match none of them.
 */
@Composable
private fun <T> ChoiceRow(
    title: String,
    options: List<Pair<T, String>>,
    selected: T?,
    onChange: (T) -> Unit,
    hint: String? = null,
    inline: Boolean = false,
    enabled: Boolean = true,
) {
    val segments = @Composable { modifier: Modifier ->
        SingleChoiceSegmentedButtonRow(modifier) {
            options.forEachIndexed { i, (option, label) ->
                SegmentedButton(
                    selected = option == selected,
                    onClick = { onChange(option) },
                    enabled = enabled,
                    shape = SegmentedButtonDefaults.itemShape(i, options.size),
                    modifier = Modifier.heightIn(min = TouchTarget),
                    icon = {},
                ) { Text(label, maxLines = 1) }
            }
        }
    }
    Column(
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.padding(vertical = 6.dp).alpha(if (enabled) 1f else DISABLED_ALPHA),
    ) {
        if (inline) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                segments(Modifier.weight(1.6f))
            }
        } else {
            Text(title, style = MaterialTheme.typography.titleMedium)
            segments(Modifier.fillMaxWidth())
        }
        if (hint != null) Text(hint, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 12.dp, bottom = 2.dp),
    )
}
