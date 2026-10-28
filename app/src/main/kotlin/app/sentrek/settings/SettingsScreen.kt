package app.sentrek.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import app.sentrek.ui.TouchTarget
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
        settings.mapTheme.stateIn(viewModelScope, SharingStarted.Eagerly, MapTheme.Auto)

    fun setMapTheme(theme: MapTheme) {
        viewModelScope.launch { settings.setMapTheme(theme) }
    }

    val autoHideControls: StateFlow<Boolean> =
        settings.autoHideControls.stateIn(viewModelScope, SharingStarted.Eagerly, true)

    fun setAutoHideControls(on: Boolean) {
        viewModelScope.launch { settings.setAutoHideControls(on) }
    }

    val fullScreen: StateFlow<Boolean> =
        settings.fullScreen.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun setFullScreen(on: Boolean) {
        viewModelScope.launch { settings.setFullScreen(on) }
    }
}

@Composable
fun SettingsScreen(onBack: () -> Unit, viewModel: SettingsViewModel = hiltViewModel()) {
    val showTrailDifficulty by viewModel.showTrailDifficulty.collectAsStateWithLifecycle()
    val gloveMode by viewModel.gloveMode.collectAsStateWithLifecycle()
    val offRouteVibration by viewModel.offRouteVibration.collectAsStateWithLifecycle()
    val theme by viewModel.theme.collectAsStateWithLifecycle()
    val mapTheme by viewModel.mapTheme.collectAsStateWithLifecycle()
    val fullScreen by viewModel.fullScreen.collectAsStateWithLifecycle()
    val autoHideControls by viewModel.autoHideControls.collectAsStateWithLifecycle()

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                MapButton(SentrekIcons.Back, stringResource(R.string.settings_back), onBack, Modifier.padding(vertical = 12.dp))
                Text(stringResource(R.string.bar_settings), style = MaterialTheme.typography.headlineMedium)
            }

            SectionTitle(stringResource(R.string.settings_appearance))
            ChoiceRow(
                title = stringResource(R.string.settings_app_theme),
                hint = stringResource(R.string.settings_theme_hint),
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

            SectionTitle(stringResource(R.string.settings_controls))
            ToggleRow(
                title = stringResource(R.string.settings_glove_mode),
                hint = stringResource(R.string.settings_glove_mode_hint),
                checked = gloveMode,
                onChange = viewModel::setGloveMode,
            )

            SectionTitle(stringResource(R.string.settings_map))
            ToggleRow(
                title = stringResource(R.string.settings_trail_difficulty),
                hint = stringResource(R.string.settings_trail_difficulty_hint),
                checked = showTrailDifficulty,
                onChange = viewModel::setShowTrailDifficulty,
            )

            SectionTitle(stringResource(R.string.settings_routes))
            ToggleRow(
                title = stringResource(R.string.settings_off_route_vibration),
                hint = stringResource(R.string.settings_off_route_vibration_hint),
                checked = offRouteVibration,
                onChange = viewModel::setOffRouteVibration,
            )

            HorizontalDivider()
            SectionTitle(stringResource(R.string.settings_about))
            Text(stringResource(R.string.settings_version, BuildConfig.VERSION_NAME), style = MaterialTheme.typography.bodyLarge)
            Text(stringResource(R.string.settings_credits), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun ToggleRow(title: String, hint: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = TouchTarget)
            .toggleable(checked, role = Role.Switch, onValueChange = onChange)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(hint, style = MaterialTheme.typography.bodyMedium)
        }
        // The whole row toggles; the switch only shows the state.
        Switch(checked = checked, onCheckedChange = null)
    }
}

/** A row of large segments, one per option, with a [title] above and a [hint] below. */
@Composable
private fun <T> ChoiceRow(title: String, hint: String?, options: List<Pair<T, String>>, selected: T, onChange: (T) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            options.forEachIndexed { i, (option, label) ->
                SegmentedButton(
                    selected = option == selected,
                    onClick = { onChange(option) },
                    shape = SegmentedButtonDefaults.itemShape(i, options.size),
                    modifier = Modifier.heightIn(min = TouchTarget),
                    icon = {},
                ) { Text(label, maxLines = 1) }
            }
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
        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
    )
}
