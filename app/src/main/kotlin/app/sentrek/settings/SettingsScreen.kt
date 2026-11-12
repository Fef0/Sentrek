package app.sentrek.settings

import app.sentrek.update.UpdateCheck
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.foundation.layout.size
import app.sentrek.update.AppUpdater
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.RadioButton
import androidx.activity.compose.LocalActivity
import app.sentrek.AppLanguage
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import app.sentrek.map.Server
import androidx.compose.ui.platform.LocalContext
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import android.widget.Toast
import androidx.core.content.ContextCompat
import android.provider.ContactsContract
import android.content.pm.PackageManager
import android.content.Intent
import android.Manifest
import android.net.Uri
import android.app.TimePickerDialog
import app.sentrek.data.TurnaroundMode
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontWeight
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.sentrek.BuildConfig
import app.sentrek.R
import app.sentrek.data.AppTheme
import app.sentrek.record.BackupRepository
import app.sentrek.data.ButtonSize
import app.sentrek.data.TextSize
import app.sentrek.data.MapTheme
import app.sentrek.data.DEFAULT_EMERGENCY_NUMBER
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
import app.sentrek.ui.ConfirmDialog
import app.sentrek.ui.Confirmation
import app.sentrek.ui.FitText
import app.sentrek.ui.MapButton
import app.sentrek.ui.SentrekIcons
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settings: SettingsRepository,
    private val backups: BackupRepository,
    val updater: AppUpdater,
) : ViewModel() {
    val autoUpdateApp: StateFlow<Boolean> =
        settings.autoUpdateApp.stateIn(viewModelScope, SharingStarted.Eagerly, true)

    fun setAutoUpdateApp(on: Boolean) {
        viewModelScope.launch { settings.setAutoUpdateApp(on) }
    }

    val useMobileData: StateFlow<Boolean> =
        settings.useMobileData.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun setUseMobileData(on: Boolean) {
        viewModelScope.launch { settings.setUseMobileData(on) }
    }

    fun backUp(target: Uri, onDone: (Boolean) -> Unit) {
        viewModelScope.launch { onDone(backups.backUp(target)) }
    }

    fun restore(source: Uri, onDone: (BackupRepository.Restored?) -> Unit) {
        viewModelScope.launch { onDone(backups.restore(source)) }
    }

    val showTrailDifficulty: StateFlow<Boolean> =
        settings.showTrailDifficulty.stateIn(viewModelScope, SharingStarted.Eagerly, true)

    val textSize: StateFlow<TextSize> =
        settings.textSize.stateIn(viewModelScope, SharingStarted.Eagerly, TextSize.Default)

    val buttonSize: StateFlow<ButtonSize> =
        settings.buttonSize.stateIn(viewModelScope, SharingStarted.Eagerly, ButtonSize.Standard)

    val powerMode: StateFlow<PowerMode> =
        settings.powerMode.stateIn(viewModelScope, SharingStarted.Eagerly, PowerMode.Normal)

    fun setPowerMode(mode: PowerMode) {
        viewModelScope.launch { settings.setPowerMode(mode) }
    }

    val sosEnabled: StateFlow<Boolean> =
        settings.sosEnabled.stateIn(viewModelScope, SharingStarted.Eagerly, true)

    fun setSosEnabled(on: Boolean) {
        viewModelScope.launch { settings.setSosEnabled(on) }
    }

    val emergencyNumber: StateFlow<String> =
        settings.emergencyNumber.stateIn(viewModelScope, SharingStarted.Eagerly, DEFAULT_EMERGENCY_NUMBER)

    fun setEmergencyNumber(number: String) {
        viewModelScope.launch { settings.setEmergencyNumber(number) }
    }

    val emergencySmsTo: StateFlow<String> =
        settings.emergencySmsTo.stateIn(viewModelScope, SharingStarted.Eagerly, "")

    val emergencyContactName: StateFlow<String> =
        settings.emergencyContactName.stateIn(viewModelScope, SharingStarted.Eagerly, "")

    fun setEmergencyContact(name: String, number: String) {
        viewModelScope.launch { settings.setEmergencyContact(name, number) }
    }

    val breadcrumbMinutes: StateFlow<Int> =
        settings.breadcrumbMinutes.stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    fun setBreadcrumbMinutes(minutes: Int) {
        viewModelScope.launch { settings.setBreadcrumbMinutes(minutes) }
    }

    val lowBatterySms: StateFlow<Boolean> =
        settings.lowBatterySms.stateIn(viewModelScope, SharingStarted.Eagerly, true)

    fun setLowBatterySms(on: Boolean) {
        viewModelScope.launch { settings.setLowBatterySms(on) }
    }

    val waterAdvice: StateFlow<Boolean> =
        settings.waterAdvice.stateIn(viewModelScope, SharingStarted.Eagerly, true)

    fun setWaterAdvice(on: Boolean) {
        viewModelScope.launch { settings.setWaterAdvice(on) }
    }

    val rainAdvice: StateFlow<Boolean> =
        settings.rainAdvice.stateIn(viewModelScope, SharingStarted.Eagerly, true)

    fun setRainAdvice(on: Boolean) {
        viewModelScope.launch { settings.setRainAdvice(on) }
    }

    val shadeAdvice: StateFlow<Boolean> =
        settings.shadeAdvice.stateIn(viewModelScope, SharingStarted.Eagerly, true)

    fun setShadeAdvice(on: Boolean) {
        viewModelScope.launch { settings.setShadeAdvice(on) }
    }

    val turnaround: StateFlow<TurnaroundMode> =
        settings.turnaround.stateIn(viewModelScope, SharingStarted.Eagerly, TurnaroundMode.Off)

    fun setTurnaround(mode: TurnaroundMode) {
        viewModelScope.launch { settings.setTurnaround(mode) }
    }

    val turnaroundMarginMinutes: StateFlow<Int> =
        settings.turnaroundMarginMinutes.stateIn(viewModelScope, SharingStarted.Eagerly, 30)

    fun setTurnaroundMarginMinutes(minutes: Int) {
        viewModelScope.launch { settings.setTurnaroundMarginMinutes(minutes) }
    }

    val turnaroundTime: StateFlow<Int> =
        settings.turnaroundTime.stateIn(viewModelScope, SharingStarted.Eagerly, 14 * 60)

    fun setTurnaroundTime(minuteOfDay: Int) {
        viewModelScope.launch { settings.setTurnaroundTime(minuteOfDay) }
    }

    val servers: StateFlow<Map<String, String>> =
        settings.servers.stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    fun setServer(server: Server, address: String) {
        viewModelScope.launch { settings.setServer(server.name, address) }
    }

    val emergencySms: StateFlow<Boolean> =
        settings.emergencySms.stateIn(viewModelScope, SharingStarted.Eagerly, true)

    fun setEmergencySms(on: Boolean) {
        viewModelScope.launch { settings.setEmergencySms(on) }
    }

    val gloveMode: StateFlow<Boolean> =
        settings.gloveMode.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun setGloveMode(on: Boolean) {
        viewModelScope.launch { settings.setGloveMode(on) }
    }

    val volumeKeysZoom: StateFlow<Boolean> =
        settings.volumeKeysZoom.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun setVolumeKeysZoom(on: Boolean) {
        viewModelScope.launch { settings.setVolumeKeysZoom(on) }
    }

    val zoomButtons: StateFlow<Boolean> =
        settings.zoomButtons.stateIn(viewModelScope, SharingStarted.Eagerly, true)

    fun setZoomButtons(show: Boolean) {
        viewModelScope.launch { settings.setZoomButtons(show) }
    }

    fun setShowTrailDifficulty(show: Boolean) {
        viewModelScope.launch { settings.setShowTrailDifficulty(show) }
    }

    val offRouteVibration: StateFlow<Boolean> =
        settings.offRouteVibration.stateIn(viewModelScope, SharingStarted.Eagerly, true)

    fun setTextSize(size: TextSize) {
        viewModelScope.launch { settings.setTextSize(size) }
    }

    fun setButtonSize(size: ButtonSize) {
        viewModelScope.launch { settings.setButtonSize(size) }
    }

    fun setOffRouteVibration(on: Boolean) {
        viewModelScope.launch { settings.setOffRouteVibration(on) }
    }

    val turnBuzz: StateFlow<Boolean> =
        settings.turnBuzz.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun setTurnBuzz(on: Boolean) {
        viewModelScope.launch { settings.setTurnBuzz(on) }
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

    val showLockButton: StateFlow<Boolean> =
        settings.showLockButton.stateIn(viewModelScope, SharingStarted.Eagerly, true)

    fun setShowLockButton(show: Boolean) {
        viewModelScope.launch { settings.setShowLockButton(show) }
    }

    val showScaleBar: StateFlow<Boolean> =
        settings.showScaleBar.stateIn(viewModelScope, SharingStarted.Eagerly, true)

    fun setShowScaleBar(show: Boolean) {
        viewModelScope.launch { settings.setShowScaleBar(show) }
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
        settings.mapMaxFps.stateIn(viewModelScope, SharingStarted.Eagerly, PowerMode.SMART_FPS)

    fun setMapMaxFps(fps: Int) {
        viewModelScope.launch { settings.setMapMaxFps(fps) }
    }

    fun resetAll() {
        viewModelScope.launch { settings.resetAll() }
    }

    val showContours: StateFlow<Boolean> =
        settings.showContours.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun setShowContours(show: Boolean) {
        viewModelScope.launch { settings.setShowContours(show) }
    }

    val showHillshade: StateFlow<Boolean> =
        settings.showHillshade.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun setShowHillshade(show: Boolean) {
        viewModelScope.launch { settings.setShowHillshade(show) }
    }

    val showMapPoints: StateFlow<Boolean> =
        settings.showMapPoints.stateIn(viewModelScope, SharingStarted.Eagerly, true)

    fun setShowMapPoints(show: Boolean) {
        viewModelScope.launch { settings.setShowMapPoints(show) }
    }

    val showTrackArrows: StateFlow<Boolean> =
        settings.showTrackArrows.stateIn(viewModelScope, SharingStarted.Eagerly, true)

    fun setShowTrackArrows(show: Boolean) {
        viewModelScope.launch { settings.setShowTrackArrows(show) }
    }

    val showSlopes: StateFlow<Boolean> =
        settings.showSlopes.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun setShowSlopes(show: Boolean) {
        viewModelScope.launch { settings.setShowSlopes(show) }
    }

    val autoUpdateMaps: StateFlow<Boolean> =
        settings.autoUpdateMaps.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun setAutoUpdateMaps(on: Boolean) {
        viewModelScope.launch { settings.setAutoUpdateMaps(on) }
    }

    val mapUpdateReminders: StateFlow<Boolean> =
        settings.mapUpdateReminders.stateIn(viewModelScope, SharingStarted.Eagerly, true)

    fun setMapUpdateReminders(on: Boolean) {
        viewModelScope.launch { settings.setMapUpdateReminders(on) }
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

    fun showGuide() {
        viewModelScope.launch { settings.setGuideSeen(false) }
    }

    val units: StateFlow<Units> =
        settings.units.stateIn(viewModelScope, SharingStarted.Eagerly, Format.units)

    fun setUnits(change: (Units) -> Units) {
        viewModelScope.launch { settings.setUnits(change(units.value)) }
    }
}

/** The pages of Settings, each opened from a row of the main one. */
private enum class SettingsPageId { Map, Controls, Appearance, Units, Alerts, Emergency, Advanced, Data, About }

@Composable
fun SettingsScreen(onBack: () -> Unit, viewModel: SettingsViewModel = hiltViewModel()) {
    // Before the pages below: back from one, the list is where it was left.
    val scroll = rememberScrollState()
    var page by rememberSaveable { mutableStateOf<SettingsPageId?>(null) }
    page?.let { open ->
        val close = { page = null }
        BackHandler(onBack = close)
        return when (open) {
            SettingsPageId.Map -> MapSettings(viewModel, close)
            SettingsPageId.Controls -> ControlSettings(viewModel, close)
            SettingsPageId.Appearance -> AppearanceSettings(viewModel, close)
            SettingsPageId.Units -> UnitSettings(viewModel, close)
            SettingsPageId.Alerts -> AlertsSettings(viewModel, close)
            SettingsPageId.Emergency -> EmergencySettings(viewModel, close)
            SettingsPageId.Advanced -> AdvancedSettings(viewModel, close)
            SettingsPageId.Data -> DataSettings(viewModel, close)
            SettingsPageId.About -> AboutSettings(viewModel, close) { viewModel.showGuide(); onBack() }
        }
    }
    val powerMode by viewModel.powerMode.collectAsStateWithLifecycle()

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .safeDrawingPadding()
                .verticalScroll(scroll)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                MapButton(SentrekIcons.Back, stringResource(R.string.settings_back), onBack, Modifier.padding(vertical = 12.dp))
                Text(stringResource(R.string.bar_settings), style = MaterialTheme.typography.headlineMedium)
            }

            // First, and right here: on a long trip, the one choice that matters most.
            ChoiceRow(
                title = stringResource(R.string.settings_battery),
                options = listOf(
                    PowerMode.Normal to stringResource(R.string.settings_battery_normal),
                    PowerMode.Saver to stringResource(R.string.settings_battery_saver_short),
                    PowerMode.Stamina to stringResource(R.string.settings_stamina_short),
                ),
                selected = powerMode,
                onChange = viewModel::setPowerMode,
                hint = when (powerMode) {
                    PowerMode.Normal -> stringResource(R.string.settings_battery_normal_hint)
                    PowerMode.Saver -> stringResource(R.string.settings_battery_saver_hint)
                    PowerMode.Stamina -> stringResource(
                        R.string.settings_stamina_hint,
                        PowerMode.STAMINA_FPS,
                        seconds(GpsTiming.Stamina.screenMs), seconds(GpsTiming.Stamina.followMs), seconds(GpsTiming.Stamina.recordMs),
                    )
                },
            )

            // The rest a page each, a line under each saying what's in it: everything at a glance.
            // By how often they're changed: the map and the controls first, units are set once.
            NavRow(stringResource(R.string.settings_map), stringResource(R.string.settings_map_about)) { page = SettingsPageId.Map }
            NavRow(stringResource(R.string.settings_controls), stringResource(R.string.settings_controls_about)) { page = SettingsPageId.Controls }
            NavRow(stringResource(R.string.settings_appearance), stringResource(R.string.settings_appearance_about)) { page = SettingsPageId.Appearance }
            NavRow(stringResource(R.string.settings_units), stringResource(R.string.settings_units_about)) { page = SettingsPageId.Units }
            NavRow(stringResource(R.string.settings_alerts_page), stringResource(R.string.settings_alerts_about)) { page = SettingsPageId.Alerts }
            NavRow(stringResource(R.string.settings_emergency_page), stringResource(R.string.settings_emergency_about)) { page = SettingsPageId.Emergency }
            NavRow(stringResource(R.string.settings_advanced), stringResource(R.string.settings_advanced_hint)) { page = SettingsPageId.Advanced }
            NavRow(stringResource(R.string.settings_data), stringResource(R.string.settings_data_about)) { page = SettingsPageId.Data }
            NavRow(stringResource(R.string.settings_about), stringResource(R.string.settings_about_about)) { page = SettingsPageId.About }
            Spacer(Modifier.height(16.dp))
        }
    }
}

/** A page of Settings: a back button, the [title], and its rows. */
@Composable
private fun SettingsPage(title: String, onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
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
                Text(title, style = MaterialTheme.typography.headlineMedium)
            }
            content()
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun MapSettings(viewModel: SettingsViewModel, onBack: () -> Unit) {
    val showContours by viewModel.showContours.collectAsStateWithLifecycle()
    val showSlopes by viewModel.showSlopes.collectAsStateWithLifecycle()
    val showHillshade by viewModel.showHillshade.collectAsStateWithLifecycle()
    val showMapPoints by viewModel.showMapPoints.collectAsStateWithLifecycle()
    val showTrackArrows by viewModel.showTrackArrows.collectAsStateWithLifecycle()
    val stamina = viewModel.powerMode.collectAsStateWithLifecycle().value == PowerMode.Stamina
    val showTrailDifficulty by viewModel.showTrailDifficulty.collectAsStateWithLifecycle()
    val showScaleBar by viewModel.showScaleBar.collectAsStateWithLifecycle()
    val rotationLocked by viewModel.rotationLocked.collectAsStateWithLifecycle()
    // What the power modes override is greyed out: it waits until they're off.
    val saving = rememberSaving(viewModel)
    SettingsPage(stringResource(R.string.settings_map), onBack) {
        if (saving) PowerNote()
        ToggleRow(
            title = stringResource(R.string.settings_map_points),
            hint = stringResource(R.string.settings_map_points_hint),
            checked = showMapPoints,
            onChange = viewModel::setShowMapPoints,
        )
        // Stamina mode hides it; battery saver leaves it.
        ToggleRow(
            enabled = !stamina,
            title = stringResource(R.string.settings_hillshade),
            hint = stringResource(R.string.settings_hillshade_hint),
            checked = showHillshade,
            onChange = viewModel::setShowHillshade,
        )
        ToggleRow(
            enabled = !saving,
            title = stringResource(R.string.settings_contours),
            hint = stringResource(R.string.settings_contours_hint),
            checked = showContours,
            onChange = viewModel::setShowContours,
        )
        ToggleRow(
            enabled = !saving,
            title = stringResource(R.string.settings_slopes),
            hint = stringResource(R.string.settings_slopes_hint),
            checked = showSlopes,
            onChange = viewModel::setShowSlopes,
        )
        ToggleRow(
            title = stringResource(R.string.settings_trail_difficulty),
            hint = stringResource(R.string.settings_trail_difficulty_hint),
            checked = showTrailDifficulty,
            onChange = viewModel::setShowTrailDifficulty,
        )
        ToggleRow(
            title = stringResource(R.string.settings_track_arrows),
            hint = stringResource(R.string.settings_track_arrows_hint),
            checked = showTrackArrows,
            onChange = viewModel::setShowTrackArrows,
        )
        ToggleRow(
            title = stringResource(R.string.settings_scale_bar),
            hint = stringResource(R.string.settings_scale_bar_hint),
            checked = showScaleBar,
            onChange = viewModel::setShowScaleBar,
        )
        ToggleRow(
            title = stringResource(R.string.settings_lock_north),
            hint = stringResource(R.string.settings_lock_north_hint),
            checked = rotationLocked,
            onChange = viewModel::setRotationLocked,
        )

        val autoUpdateMaps by viewModel.autoUpdateMaps.collectAsStateWithLifecycle()
        val mapUpdateReminders by viewModel.mapUpdateReminders.collectAsStateWithLifecycle()
        SectionTitle(stringResource(R.string.settings_map_updates))
        ToggleRow(
            title = stringResource(R.string.settings_auto_update_maps),
            hint = stringResource(R.string.settings_auto_update_maps_hint),
            checked = autoUpdateMaps,
            onChange = viewModel::setAutoUpdateMaps,
        )
        ToggleRow(
            // Maps that update themselves need no reminder.
            enabled = !autoUpdateMaps,
            title = stringResource(R.string.settings_map_reminders),
            hint = stringResource(R.string.settings_map_reminders_hint),
            checked = mapUpdateReminders,
            onChange = viewModel::setMapUpdateReminders,
        )
    }
}

@Composable
private fun ControlSettings(viewModel: SettingsViewModel, onBack: () -> Unit) {
    val buttonSize by viewModel.buttonSize.collectAsStateWithLifecycle()
    val gloveMode by viewModel.gloveMode.collectAsStateWithLifecycle()
    val volumeKeysZoom by viewModel.volumeKeysZoom.collectAsStateWithLifecycle()
    val zoomButtons by viewModel.zoomButtons.collectAsStateWithLifecycle()
    val showLockButton by viewModel.showLockButton.collectAsStateWithLifecycle()
    val autoHideControls by viewModel.autoHideControls.collectAsStateWithLifecycle()
    val confirmExit by viewModel.confirmExit.collectAsStateWithLifecycle()
    SettingsPage(stringResource(R.string.settings_controls), onBack) {
        // First: with gloves on, nothing else on this page works without it.
        ToggleRow(
            title = stringResource(R.string.settings_glove_mode),
            hint = stringResource(R.string.settings_glove_mode_hint),
            checked = gloveMode,
            onChange = viewModel::setGloveMode,
        )
        // Glove mode decides these three: greyed out until it's off.
        ChoiceRow(
            enabled = !gloveMode,
            title = stringResource(R.string.settings_button_size),
            hint = stringResource(if (gloveMode) R.string.settings_button_size_gloves else R.string.settings_button_size_hint),
            options = listOf(
                ButtonSize.Standard to stringResource(R.string.settings_size_standard),
                ButtonSize.Large to stringResource(R.string.settings_size_large),
                ButtonSize.Largest to stringResource(R.string.settings_size_largest),
            ),
            selected = buttonSize,
            onChange = viewModel::setButtonSize,
        )
        ToggleRow(
            enabled = !gloveMode,
            title = stringResource(R.string.settings_volume_zoom),
            hint = stringResource(if (gloveMode) R.string.settings_volume_zoom_gloves else R.string.settings_volume_zoom_hint),
            checked = gloveMode || volumeKeysZoom,
            onChange = viewModel::setVolumeKeysZoom,
        )
        ToggleRow(
            enabled = !gloveMode,
            title = stringResource(R.string.settings_zoom_buttons),
            hint = stringResource(if (gloveMode) R.string.settings_zoom_buttons_gloves else R.string.settings_zoom_buttons_hint),
            checked = gloveMode || zoomButtons,
            onChange = viewModel::setZoomButtons,
        )
        ToggleRow(
            title = stringResource(R.string.settings_lock_button),
            hint = stringResource(R.string.settings_lock_button_hint),
            checked = showLockButton,
            onChange = viewModel::setShowLockButton,
        )
        ToggleRow(
            title = stringResource(R.string.settings_auto_hide),
            hint = stringResource(R.string.settings_auto_hide_hint),
            checked = autoHideControls,
            onChange = viewModel::setAutoHideControls,
        )
        ToggleRow(
            title = stringResource(R.string.settings_confirm_exit),
            hint = stringResource(R.string.settings_confirm_exit_hint),
            checked = confirmExit,
            onChange = viewModel::setConfirmExit,
        )
    }
}

@Composable
private fun AppearanceSettings(viewModel: SettingsViewModel, onBack: () -> Unit) {
    val textSize by viewModel.textSize.collectAsStateWithLifecycle()
    val theme by viewModel.theme.collectAsStateWithLifecycle()
    val mapTheme by viewModel.mapTheme.collectAsStateWithLifecycle()
    val fullScreen by viewModel.fullScreen.collectAsStateWithLifecycle()
    val saving = rememberSaving(viewModel)
    val activity = LocalActivity.current
    val context = LocalContext.current
    var language by remember { mutableStateOf(AppLanguage.current(context)) }
    SettingsPage(stringResource(R.string.settings_appearance), onBack) {
        if (saving) PowerNote()
        LanguageRow(language) { tag ->
            language = tag
            activity?.let { AppLanguage.set(it, tag) }
        }
        ChoiceRow(
            title = stringResource(R.string.settings_text_size),
            hint = stringResource(R.string.settings_text_size_hint),
            options = listOf(
                TextSize.Small to stringResource(R.string.settings_size_small),
                TextSize.Default to stringResource(R.string.settings_size_default),
                TextSize.Large to stringResource(R.string.settings_size_large),
                TextSize.Largest to stringResource(R.string.settings_size_largest),
            ),
            selected = textSize,
            onChange = viewModel::setTextSize,
        )
        ChoiceRow(
            enabled = !saving,
            title = stringResource(R.string.settings_app_theme),
            hint = stringResource(R.string.settings_app_theme_hint),
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
    }
}

@Composable
private fun UnitSettings(viewModel: SettingsViewModel, onBack: () -> Unit) {
    val units by viewModel.units.collectAsStateWithLifecycle()
    SettingsPage(stringResource(R.string.settings_units), onBack) {
        // Metric or imperial sets distance, height and speed; the rest stays as chosen.
        val system = unitSystem(units)
        ChoiceRow(
            title = stringResource(R.string.settings_units_system),
            hint = stringResource(R.string.settings_units_system_hint),
            options = listOf(
                Units.Metric to stringResource(R.string.settings_units_metric),
                Units.Imperial to stringResource(R.string.settings_units_imperial),
            ),
            selected = system,
            onChange = { preset ->
                viewModel.setUnits { it.copy(distance = preset.distance, elevation = preset.elevation, speed = preset.speed) }
            },
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
            )
            ChoiceRow(
                title = stringResource(R.string.settings_units_elevation),
                options = listOf(ElevationUnit.Meters to "m", ElevationUnit.Feet to "ft"),
                selected = units.elevation,
                onChange = { unit -> viewModel.setUnits { it.copy(elevation = unit) } },
            )
            ChoiceRow(
                title = stringResource(R.string.settings_units_speed),
                options = listOf(SpeedUnit.KilometersPerHour to "km/h", SpeedUnit.MilesPerHour to "mph", SpeedUnit.Knots to "kn"),
                selected = units.speed,
                onChange = { unit -> viewModel.setUnits { it.copy(speed = unit) } },
            )
            ChoiceRow(
                title = stringResource(R.string.settings_units_slope),
                options = listOf(SlopeUnit.Degrees to "12°", SlopeUnit.Percent to "21%"),
                selected = units.slope,
                onChange = { unit -> viewModel.setUnits { it.copy(slope = unit) } },
            )
            ChoiceRow(
                title = stringResource(R.string.settings_units_bearing),
                options = listOf(BearingUnit.Degrees to "245°", BearingUnit.Mils to "4356 mil"),
                selected = units.bearing,
                onChange = { unit -> viewModel.setUnits { it.copy(bearing = unit) } },
            )
        }
    }
}

@Composable
private fun DataSettings(viewModel: SettingsViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val saveTo = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) {
            viewModel.backUp(uri) { ok ->
                Toast.makeText(context, if (ok) R.string.settings_backup_done else R.string.settings_backup_failed, Toast.LENGTH_LONG).show()
            }
        }
    }
    val openFrom = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            viewModel.restore(uri) { restored ->
                val text = restored?.let { context.getString(R.string.settings_restore_done, it.tracks.size, it.places.size) }
                    ?: context.getString(R.string.settings_restore_failed)
                Toast.makeText(context, text, Toast.LENGTH_LONG).show()
            }
        }
    }
    SettingsPage(stringResource(R.string.settings_data), onBack) {
        Text(stringResource(R.string.settings_data_hint), style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
            OutlinedButton(
                onClick = { saveTo.launch(BackupRepository.fileName()) },
                modifier = Modifier.weight(1f).heightIn(min = TouchTarget),
            ) { FitText(stringResource(R.string.settings_backup)) }
            OutlinedButton(
                onClick = { openFrom.launch(arrayOf("application/zip", "application/octet-stream")) },
                modifier = Modifier.weight(1f).heightIn(min = TouchTarget),
            ) { FitText(stringResource(R.string.settings_restore)) }
        }
    }
}

@Composable
private fun AboutSettings(viewModel: SettingsViewModel, onBack: () -> Unit, onShowGuide: () -> Unit) {
    SettingsPage(stringResource(R.string.settings_about), onBack) {
        Text(stringResource(R.string.settings_version, BuildConfig.VERSION_NAME), style = MaterialTheme.typography.bodyLarge)
        // GitHub builds only: F-Droid updates the app itself.
        if (viewModel.updater.available) {
            val autoUpdate by viewModel.autoUpdateApp.collectAsStateWithLifecycle()
            val waiting by viewModel.updater.waiting.collectAsStateWithLifecycle()
            val activity = LocalActivity.current
            ToggleRow(
                title = stringResource(R.string.settings_auto_update_app),
                hint = stringResource(R.string.settings_auto_update_app_hint),
                checked = autoUpdate,
                onChange = viewModel::setAutoUpdateApp,
            )
            val check by viewModel.updater.check.collectAsStateWithLifecycle()
            val version = waiting
            if (version != null && !check.busy) {
                Button(
                    onClick = { activity?.let(viewModel.updater::install) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = TouchTarget).padding(vertical = 4.dp),
                ) { Text(stringResource(R.string.settings_install_update, version)) }
            } else {
                OutlinedButton(
                    onClick = { viewModel.updater.checkNow() },
                    enabled = !check.busy,
                    modifier = Modifier.fillMaxWidth().heightIn(min = TouchTarget).padding(vertical = 4.dp),
                ) { Text(stringResource(R.string.settings_check_updates)) }
            }
            UpdateCheckStatus(check, onOverMobileData = { viewModel.updater.checkNow(overMobileData = true) })
        }
        OutlinedButton(
            onClick = onShowGuide,
            modifier = Modifier.fillMaxWidth().heightIn(min = TouchTarget).padding(vertical = 4.dp),
        ) { Text(stringResource(R.string.settings_show_guide)) }
        Text(
            stringResource(R.string.settings_credits),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

/** Where "Check for updates" is at: a spinner, the download's progress, or what it found. */
@Composable
private fun UpdateCheckStatus(check: UpdateCheck, onOverMobileData: () -> Unit) {
    val text = when (check) {
        UpdateCheck.Idle -> return
        UpdateCheck.Looking -> stringResource(R.string.update_looking)
        is UpdateCheck.Downloading -> stringResource(R.string.update_downloading, check.version, (check.fraction * 100).toInt())
        is UpdateCheck.Installing -> stringResource(R.string.update_installing, check.version)
        is UpdateCheck.UpToDate -> stringResource(R.string.update_up_to_date, check.version)
        is UpdateCheck.OnMobileData -> stringResource(R.string.update_on_mobile_data, check.version)
        is UpdateCheck.AfterHike -> stringResource(R.string.update_after_hike, check.version)
        UpdateCheck.Failed -> stringResource(R.string.update_failed)
    }
    Column(Modifier.padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (check is UpdateCheck.Looking || check is UpdateCheck.Installing) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            }
            Text(text, style = MaterialTheme.typography.bodyMedium)
        }
        if (check is UpdateCheck.Downloading) {
            LinearProgressIndicator(progress = { check.fraction }, modifier = Modifier.fillMaxWidth())
        }
        if (check is UpdateCheck.OnMobileData) {
            OutlinedButton(
                onClick = onOverMobileData,
                modifier = Modifier.fillMaxWidth().heightIn(min = TouchTarget),
            ) { Text(stringResource(R.string.update_download_anyway)) }
        }
    }
}

/** Battery saver or stamina mode on: what they override waits, greyed out. */
@Composable
private fun rememberSaving(viewModel: SettingsViewModel): Boolean {
    val powerMode by viewModel.powerMode.collectAsStateWithLifecycle()
    return powerMode != PowerMode.Normal
}

/** Why some rows are greyed out on this page. */
@Composable
private fun PowerNote() {
    Text(
        stringResource(R.string.settings_power_note),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(vertical = 4.dp),
    )
}

/** Metric or imperial when distance, height and speed all match one; null for a mix. */
private fun unitSystem(units: Units): Units? = listOf(Units.Metric, Units.Imperial).firstOrNull {
    it.distance == units.distance && it.elevation == units.elevation && it.speed == units.speed
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

/**
 * What the app warns about on the way: leaving the route, the last water, rain and storms,
 * time to turn back. On a page of their own, with a summary on the main one.
 */
@Composable
private fun AlertsSettings(viewModel: SettingsViewModel, onBack: () -> Unit) {
    val offRouteVibration by viewModel.offRouteVibration.collectAsStateWithLifecycle()
    val turnBuzz by viewModel.turnBuzz.collectAsStateWithLifecycle()
    val waterAdvice by viewModel.waterAdvice.collectAsStateWithLifecycle()
    val rainAdvice by viewModel.rainAdvice.collectAsStateWithLifecycle()
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
                Text(stringResource(R.string.settings_alerts_page), style = MaterialTheme.typography.headlineMedium)
            }
            ToggleRow(
                title = stringResource(R.string.settings_off_route_vibration),
                hint = stringResource(R.string.settings_off_route_vibration_hint),
                checked = offRouteVibration,
                onChange = viewModel::setOffRouteVibration,
            )
            ToggleRow(
                title = stringResource(R.string.settings_turn_buzz),
                hint = stringResource(R.string.settings_turn_buzz_hint),
                checked = turnBuzz,
                onChange = viewModel::setTurnBuzz,
            )
            ToggleRow(
                title = stringResource(R.string.settings_water_advice),
                hint = stringResource(R.string.settings_water_advice_hint),
                checked = waterAdvice,
                onChange = viewModel::setWaterAdvice,
            )
            ToggleRow(
                title = stringResource(R.string.settings_rain_advice),
                hint = stringResource(R.string.settings_rain_advice_hint),
                checked = rainAdvice,
                onChange = viewModel::setRainAdvice,
            )
            val shadeAdvice by viewModel.shadeAdvice.collectAsStateWithLifecycle()
            ToggleRow(
                title = stringResource(R.string.settings_shade_advice),
                hint = stringResource(R.string.settings_shade_advice_hint),
                checked = shadeAdvice,
                onChange = viewModel::setShadeAdvice,
            )
            TurnaroundRows(viewModel)
            Spacer(Modifier.height(16.dp))
        }
    }
}

/** Map frame rate and GPS timing: rarely changed, so on a page of their own. */
@Composable
private fun EmergencySettings(viewModel: SettingsViewModel, onBack: () -> Unit) {
    val emergencySms by viewModel.emergencySms.collectAsStateWithLifecycle()
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
                Text(stringResource(R.string.settings_emergency_page), style = MaterialTheme.typography.headlineMedium)
            }
            val emergencyNumber by viewModel.emergencyNumber.collectAsStateWithLifecycle()
            val emergencySmsTo by viewModel.emergencySmsTo.collectAsStateWithLifecycle()
            val sos by viewModel.sosEnabled.collectAsStateWithLifecycle()
            ToggleRow(
                icon = SentrekIcons.Emergency,
                title = stringResource(R.string.settings_sos),
                hint = stringResource(R.string.settings_sos_hint),
                checked = sos,
                onChange = viewModel::setSosEnabled,
            )
            // Off: the rest of the section waits, greyed out, until it's on again.
            TextSettingRow(
                enabled = sos,
                title = stringResource(R.string.settings_emergency_number),
                value = emergencyNumber,
                hint = stringResource(R.string.settings_emergency_number_hint),
                phone = true,
                onChange = viewModel::setEmergencyNumber,
            )
            ToggleRow(
                enabled = sos,
                title = stringResource(R.string.settings_emergency_sms),
                hint = stringResource(R.string.settings_emergency_sms_hint),
                checked = emergencySms,
                onChange = viewModel::setEmergencySms,
            )
            val contactName by viewModel.emergencyContactName.collectAsStateWithLifecycle()
            ContactRow(
                enabled = sos,
                name = contactName,
                number = emergencySmsTo,
                onChange = viewModel::setEmergencyContact,
            )
            val breadcrumbsOn = sos && emergencySmsTo.isNotEmpty()
            BreadcrumbRow(enabled = breadcrumbsOn, viewModel = viewModel)
            val minutes by viewModel.breadcrumbMinutes.collectAsStateWithLifecycle()
            val lowBattery by viewModel.lowBatterySms.collectAsStateWithLifecycle()
            // Only with the texts on: it's one more of them.
            val lowBatteryOn = breadcrumbsOn && minutes > 0
            ToggleRow(
                enabled = lowBatteryOn,
                title = stringResource(R.string.settings_low_battery_sms),
                hint = stringResource(R.string.settings_low_battery_sms_hint),
                checked = lowBatteryOn && lowBattery,
                onChange = viewModel::setLowBatterySms,
            )
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun AdvancedSettings(viewModel: SettingsViewModel, onBack: () -> Unit) {
    val fps by viewModel.mapMaxFps.collectAsStateWithLifecycle()
    val gps by viewModel.gpsTiming.collectAsStateWithLifecycle()
    val stamina = viewModel.powerMode.collectAsStateWithLifecycle().value == PowerMode.Stamina
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

            SectionTitle(stringResource(R.string.settings_battery))
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
                options = PowerMode.FPS_CHOICES.map {
                    it to when (it) {
                        0 -> stringResource(R.string.settings_map_fps_no_cap)
                        PowerMode.SMART_FPS -> stringResource(R.string.settings_map_fps_smart)
                        else -> "$it"
                    }
                },
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

            SectionTitle(stringResource(R.string.settings_downloads))
            val useMobileData by viewModel.useMobileData.collectAsStateWithLifecycle()
            ToggleRow(
                title = stringResource(R.string.settings_mobile_data),
                hint = stringResource(R.string.settings_mobile_data_hint),
                checked = useMobileData,
                onChange = viewModel::setUseMobileData,
            )

            SectionTitle(stringResource(R.string.settings_servers))
            Text(stringResource(R.string.settings_servers_hint), style = MaterialTheme.typography.bodyMedium)
            val servers by viewModel.servers.collectAsStateWithLifecycle()
            for (server in Server.entries) {
                val custom = servers[server.name]
                TextSettingRow(
                    title = stringResource(serverTitle(server)),
                    value = custom ?: stringResource(R.string.settings_server_default, server.defaults.first().removePrefix("https://").substringBefore('/')),
                    editValue = custom.orEmpty(),
                    hint = stringResource(serverHint(server)),
                    placeholder = server.defaults.first(),
                    validate = Server::isValid,
                    onChange = { viewModel.setServer(server, it) },
                )
            }

            // Here, a page away from the main settings: out of reach of a stray tap.
            var askReset by remember { mutableStateOf(false) }
            OutlinedButton(
                onClick = { askReset = true },
                modifier = Modifier.fillMaxWidth().heightIn(min = TouchTarget).padding(vertical = 4.dp),
            ) { Text(stringResource(R.string.settings_reset), color = MaterialTheme.colorScheme.error) }
            if (askReset) {
                ConfirmDialog(
                    Confirmation(
                        title = stringResource(R.string.settings_reset_title),
                        text = stringResource(R.string.settings_reset_text),
                        confirmLabel = stringResource(R.string.settings_reset_confirm),
                        destructive = true,
                        onConfirm = viewModel::resetAll,
                    ),
                ) { askReset = false }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

private fun seconds(ms: Long): String = "${ms / 1_000} s"

/** Material's look for disabled content. */
private const val DISABLED_ALPHA = 0.38f

private fun serverTitle(server: Server) = when (server) {
    Server.Tiles -> R.string.settings_server_tiles
    Server.Terrain -> R.string.settings_server_terrain
    Server.Trails -> R.string.settings_server_trails
}

private fun serverHint(server: Server) = when (server) {
    Server.Tiles -> R.string.settings_server_tiles_hint
    Server.Terrain -> R.string.settings_server_terrain_hint
    Server.Trails -> R.string.settings_server_trails_hint
}

/**
 * A value typed in (a phone number, an address): shown in the row, edited in a dialog.
 * An empty value goes back to the default. [validate]: what may be saved.
 */
@Composable
private fun TextSettingRow(
    title: String,
    value: String,
    onChange: (String) -> Unit,
    hint: String? = null,
    editValue: String = value,
    placeholder: String? = null,
    phone: Boolean = false,
    enabled: Boolean = true,
    validate: (String) -> Boolean = { true },
) {
    var editing by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = TouchTarget)
            .clickable(enabled = enabled, role = Role.Button) { editing = true }
            .padding(vertical = 6.dp)
            .alpha(if (enabled) 1f else DISABLED_ALPHA),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(value, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
            if (hint != null) Text(hint, style = MaterialTheme.typography.bodyMedium)
        }
    }
    if (editing) {
        // All selected: typing replaces the old value, no fiddling with the cursor.
        var field by remember { mutableStateOf(TextFieldValue(editValue, TextRange(0, editValue.length))) }
        val text = field.text
        val valid = text.isBlank() || validate(text.trim())
        AlertDialog(
            onDismissRequest = { editing = false },
            title = { Text(title) },
            text = {
                val focus = remember { FocusRequester() }
                LaunchedEffect(Unit) { focus.requestFocus() }
                OutlinedTextField(
                    value = field,
                    onValueChange = { field = it },
                    singleLine = true,
                    isError = !valid,
                    placeholder = placeholder?.let { { Text(it) } },
                    supportingText = if (!valid) ({ Text(stringResource(R.string.settings_invalid)) }) else null,
                    keyboardOptions = KeyboardOptions(keyboardType = if (phone) KeyboardType.Phone else KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
            },
            confirmButton = {
                Button(
                    onClick = { onChange(text.trim()); editing = false },
                    enabled = valid,
                    modifier = Modifier.heightIn(min = TouchTarget),
                ) { Text(stringResource(R.string.settings_save)) }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { onChange(""); editing = false }, modifier = Modifier.heightIn(min = TouchTarget)) {
                        Text(stringResource(R.string.settings_default))
                    }
                    TextButton(onClick = { editing = false }, modifier = Modifier.heightIn(min = TouchTarget)) {
                        Text(stringResource(R.string.settings_cancel))
                    }
                }
            },
        )
    }
}

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

/**
 * The app's language, a tap away from a list of every translation. Each under its own name,
 * so it's found by someone who can't read the language the app is in.
 */
@Composable
private fun LanguageRow(selected: String?, onChange: (String?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val system = stringResource(R.string.settings_language_system)
    NavRow(
        stringResource(R.string.settings_language),
        selected?.let(AppLanguage::name) ?: system,
    ) { open = true }
    if (!open) return
    val choices = listOf(null) + AppLanguage.available.sortedBy(AppLanguage::name)
    AlertDialog(
        onDismissRequest = { open = false },
        title = { Text(stringResource(R.string.settings_language)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                for (tag in choices) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = TouchTarget)
                            .selectable(selected = tag == selected, role = Role.RadioButton) {
                                open = false
                                if (tag != selected) onChange(tag)
                            },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        RadioButton(selected = tag == selected, onClick = null)
                        Text(tag?.let(AppLanguage::name) ?: system, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { open = false }, modifier = Modifier.heightIn(min = TouchTarget)) {
                Text(stringResource(R.string.settings_cancel))
            }
        },
    )
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
 * Large segments, one per option, under the [title]. [selected] may match none of them.
 */
@Composable
private fun <T> ChoiceRow(
    title: String,
    options: List<Pair<T, String>>,
    selected: T?,
    onChange: (T) -> Unit,
    hint: String? = null,
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
                ) { FitText(label) }
            }
        }
    }
    Column(
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.padding(vertical = 6.dp).alpha(if (enabled) 1f else DISABLED_ALPHA),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        segments(Modifier.fillMaxWidth())
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

/**
 * The emergency contact, picked from the phone's contacts. The picker hands over just the
 * one number chosen: no permission to read the contacts is needed.
 */
@Composable
private fun ContactRow(enabled: Boolean, name: String, number: String, onChange: (String, String) -> Unit) {
    val context = LocalContext.current
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uri = result.data?.data ?: return@rememberLauncherForActivityResult
        val columns = arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER)
        runCatching {
            context.contentResolver.query(uri, columns, null, null, null)?.use { c ->
                if (c.moveToFirst()) onChange(c.getString(0).orEmpty(), c.getString(1).orEmpty())
            }
        }.onFailure { Toast.makeText(context, R.string.emergency_no_app, Toast.LENGTH_LONG).show() }
    }
    val choose = {
        runCatching {
            pick.launch(Intent(Intent.ACTION_PICK).setType(ContactsContract.CommonDataKinds.Phone.CONTENT_TYPE))
        }.onFailure { Toast.makeText(context, R.string.emergency_no_app, Toast.LENGTH_LONG).show() }
        Unit
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = TouchTarget)
            .clickable(enabled = enabled, role = Role.Button, onClick = choose)
            .padding(vertical = 6.dp)
            .alpha(if (enabled) 1f else DISABLED_ALPHA),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.settings_emergency_contact), style = MaterialTheme.typography.titleMedium)
            val shown = when {
                number.isEmpty() -> stringResource(R.string.settings_emergency_contact_none)
                name.isEmpty() -> number
                else -> "$name · $number"
            }
            Text(shown, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
            Text(stringResource(R.string.settings_emergency_contact_hint), style = MaterialTheme.typography.bodyMedium)
        }
        if (number.isNotEmpty()) {
            TextButton(onClick = { onChange("", "") }, enabled = enabled, modifier = Modifier.heightIn(min = TouchTarget)) {
                Text(stringResource(R.string.settings_emergency_contact_remove))
            }
        }
    }
}

/**
 * Breadcrumbs: the position by SMS every 30 or 60 minutes while recording. Turning them on
 * says first what they may cost, then asks Android for permission to send texts.
 */
@Composable
private fun BreadcrumbRow(enabled: Boolean, viewModel: SettingsViewModel) {
    val minutes by viewModel.breadcrumbMinutes.collectAsStateWithLifecycle()
    var asking by remember { mutableStateOf<Int?>(null) }
    val context = LocalContext.current
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val chosen = asking
        asking = null
        if (granted && chosen != null) viewModel.setBreadcrumbMinutes(chosen)
    }
    var warning by remember { mutableStateOf<Int?>(null) }
    val canSend = {
        ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED
    }
    val choose = { choice: Int ->
        when {
            choice == 0 -> viewModel.setBreadcrumbMinutes(0)
            minutes == 0 || !canSend() -> warning = choice
            else -> viewModel.setBreadcrumbMinutes(choice)
        }
    }
    ChoiceRow(
        title = stringResource(R.string.settings_breadcrumbs),
        options = listOf(
            0 to stringResource(R.string.settings_breadcrumbs_off),
            30 to stringResource(R.string.settings_breadcrumbs_every, 30),
            60 to stringResource(R.string.settings_breadcrumbs_every, 60),
        ),
        // Without a contact nothing is sent, whatever was chosen.
        selected = if (enabled) minutes else 0,
        onChange = choose,
        hint = stringResource(R.string.settings_breadcrumbs_hint),
        enabled = enabled,
    )
    // The cost on a line of its own, where it can't be skimmed over.
    Text(
        stringResource(R.string.settings_breadcrumbs_cost_warning),
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.alpha(if (enabled) 1f else DISABLED_ALPHA),
    )
    warning?.let { choice ->
        ConfirmDialog(
            Confirmation(
                title = stringResource(R.string.settings_breadcrumbs_cost_title),
                // Start and end, plus one each period.
                text = stringResource(R.string.settings_breadcrumbs_cost_text, choice, 8 * 60 / choice + 2),
                warning = stringResource(R.string.settings_breadcrumbs_cost_warning),
                confirmLabel = stringResource(R.string.settings_breadcrumbs_cost_confirm),
                onConfirm = {
                    if (canSend()) {
                        viewModel.setBreadcrumbMinutes(choice)
                    } else {
                        asking = choice
                        permission.launch(Manifest.permission.SEND_SMS)
                    }
                },
            ),
        ) { warning = null }
    }
}

/**
 * The turnaround alarm, off unless chosen: before dark (when the end of the followed route
 * comes too close to sunset at the user's pace), or at a time of day.
 */
@Composable
private fun TurnaroundRows(viewModel: SettingsViewModel) {
    val mode by viewModel.turnaround.collectAsStateWithLifecycle()
    val margin by viewModel.turnaroundMarginMinutes.collectAsStateWithLifecycle()
    val minuteOfDay by viewModel.turnaroundTime.collectAsStateWithLifecycle()
    val context = LocalContext.current
    ChoiceRow(
        title = stringResource(R.string.settings_turnaround),
        options = listOf(
            TurnaroundMode.Off to stringResource(R.string.settings_turnaround_off),
            TurnaroundMode.BeforeDark to stringResource(R.string.settings_turnaround_dark),
            TurnaroundMode.AtTime to stringResource(R.string.settings_turnaround_time),
        ),
        selected = mode,
        onChange = viewModel::setTurnaround,
        hint = when (mode) {
            TurnaroundMode.Off -> stringResource(R.string.settings_turnaround_hint)
            TurnaroundMode.BeforeDark -> stringResource(R.string.settings_turnaround_dark_hint)
            TurnaroundMode.AtTime -> stringResource(R.string.settings_turnaround_time_hint)
        },
    )
    when (mode) {
        TurnaroundMode.Off -> Unit
        TurnaroundMode.BeforeDark -> ChoiceRow(
            title = stringResource(R.string.settings_turnaround_margin),
            options = listOf(
                0 to stringResource(R.string.settings_turnaround_margin_none),
                30 to stringResource(R.string.settings_turnaround_margin_minutes, 30),
                60 to stringResource(R.string.settings_turnaround_margin_hour),
            ),
            selected = margin,
            onChange = viewModel::setTurnaroundMarginMinutes,
        )
        TurnaroundMode.AtTime -> {
            val shown = android.text.format.DateFormat.getTimeFormat(context).format(
                java.util.Calendar.getInstance().apply {
                    set(java.util.Calendar.HOUR_OF_DAY, minuteOfDay / 60)
                    set(java.util.Calendar.MINUTE, minuteOfDay % 60)
                }.time,
            )
            NavRow(stringResource(R.string.settings_turnaround_at), shown) {
                TimePickerDialog(
                    context,
                    { _, hour, minute -> viewModel.setTurnaroundTime(hour * 60 + minute) },
                    minuteOfDay / 60,
                    minuteOfDay % 60,
                    android.text.format.DateFormat.is24HourFormat(context),
                ).show()
            }
        }
    }
}
