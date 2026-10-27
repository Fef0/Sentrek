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
import app.sentrek.data.SettingsRepository
import app.sentrek.ui.GloveTarget
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

    fun setShowTrailDifficulty(show: Boolean) {
        viewModelScope.launch { settings.setShowTrailDifficulty(show) }
    }
}

@Composable
fun SettingsScreen(onBack: () -> Unit, viewModel: SettingsViewModel = hiltViewModel()) {
    val showTrailDifficulty by viewModel.showTrailDifficulty.collectAsStateWithLifecycle()

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

            SectionTitle(stringResource(R.string.settings_map))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = GloveTarget)
                    .toggleable(showTrailDifficulty, role = Role.Switch, onValueChange = viewModel::setShowTrailDifficulty)
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.settings_trail_difficulty), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.settings_trail_difficulty_hint), style = MaterialTheme.typography.bodyMedium)
                }
                // The whole row toggles; the switch only shows the state.
                Switch(checked = showTrailDifficulty, onCheckedChange = null)
            }

            HorizontalDivider()
            SectionTitle(stringResource(R.string.settings_about))
            Text(stringResource(R.string.settings_version, BuildConfig.VERSION_NAME), style = MaterialTheme.typography.bodyLarge)
            Text(stringResource(R.string.settings_credits), style = MaterialTheme.typography.bodyMedium)
        }
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
