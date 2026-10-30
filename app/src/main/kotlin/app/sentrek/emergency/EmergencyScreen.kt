package app.sentrek.emergency

import app.sentrek.geo.Geo
import kotlinx.coroutines.flow.map
import app.sentrek.record.SignalWatch
import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.location.Location
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.sentrek.R
import app.sentrek.data.DEFAULT_EMERGENCY_NUMBER
import app.sentrek.data.SettingsRepository
import app.sentrek.geo.GridRefs
import app.sentrek.geo.LatLon
import app.sentrek.geo.units.CoordinateFormat
import app.sentrek.geo.units.UnitFormat
import app.sentrek.record.LocationRepository
import app.sentrek.ui.Format
import app.sentrek.ui.MapButton
import app.sentrek.ui.SentrekIcons
import app.sentrek.ui.TouchTarget
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class EmergencyViewModel @Inject constructor(
    private val location: LocationRepository,
    settings: SettingsRepository,
    signal: SignalWatch,
) : ViewModel() {
    private val permissionChecks = MutableStateFlow(0)

    /** Every second while the screen is open, whatever the power mode: this is when it matters. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val fix: StateFlow<Location?> = permissionChecks
        .flatMapLatest { if (location.hasPermission()) location.locations(FIX_MS) else flowOf(null) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val smsButton: StateFlow<Boolean> =
        settings.emergencySms.stateIn(viewModelScope, SharingStarted.Eagerly, true)

    val number: StateFlow<String> =
        settings.emergencyNumber.stateIn(viewModelScope, SharingStarted.Eagerly, DEFAULT_EMERGENCY_NUMBER)

    val smsTo: StateFlow<String> =
        settings.emergencySmsTo.stateIn(viewModelScope, SharingStarted.Eagerly, "")

    /** As in the phone's contacts; empty for a number typed in before contacts could be picked. */
    val contactName: StateFlow<String> =
        settings.emergencyContactName.stateIn(viewModelScope, SharingStarted.Eagerly, "")

    /** The last place with a phone signal, while there is none here. */
    val lastSignal: StateFlow<SignalWatch.Spot?> = signal.state
        .map { s -> s?.last?.takeIf { !s.hasSignal } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun hasPermission() = location.hasPermission()

    fun onPermissionChanged() {
        permissionChecks.value++
    }

    private companion object {
        const val FIX_MS = 1_000L
    }
}

/**
 * Where am I, for someone else: big coordinates in every format a rescue service may ask for,
 * and ways to pass them on that need no data connection (a call, an SMS).
 */
@Composable
fun EmergencyScreen(
    onBack: () -> Unit,
    /** Shows the way to a place on the map: the last one with signal. */
    onGoTo: (name: String, position: LatLon) -> Unit,
    viewModel: EmergencyViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val fix by viewModel.fix.collectAsStateWithLifecycle()
    val smsButton by viewModel.smsButton.collectAsStateWithLifecycle()
    val number by viewModel.number.collectAsStateWithLifecycle()
    val smsTo by viewModel.smsTo.collectAsStateWithLifecycle()
    val contactName by viewModel.contactName.collectAsStateWithLifecycle()
    val lastSignal by viewModel.lastSignal.collectAsStateWithLifecycle()
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        viewModel.onPermissionChanged()
    }
    val wide = Modifier.fillMaxWidth().heightIn(min = TouchTarget)

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                MapButton(SentrekIcons.Back, stringResource(R.string.emergency_back), onBack, Modifier.padding(vertical = 12.dp))
                Text(stringResource(R.string.emergency_title), style = MaterialTheme.typography.headlineMedium)
            }

            // First, and without data: 112 unless the user chose another number in Settings.
            Button(
                onClick = { dial(context, number) },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                modifier = wide,
            ) { Text(stringResource(R.string.emergency_call, number), style = MaterialTheme.typography.titleLarge) }

            // No signal: the call above may not get out. Where it last did.
            lastSignal?.let { spot ->
                val here = fix?.let { LatLon(it.latitude, it.longitude) }
                val time = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(spot.time))
                Text(
                    if (here != null) {
                        stringResource(
                            R.string.emergency_last_signal,
                            Format.distance(Geo.distance(here, spot.position)),
                            Format.bearing(Geo.bearing(here, spot.position)),
                            time,
                        )
                    } else {
                        stringResource(R.string.emergency_last_signal_time, time)
                    },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                val name = stringResource(R.string.signal_goto_name)
                OutlinedButton(onClick = { onGoTo(name, spot.position) }, modifier = wide) {
                    Text(stringResource(R.string.signal_goto))
                }
            }

            val here = fix
            when {
                !viewModel.hasPermission() -> {
                    Text(stringResource(R.string.emergency_no_permission), style = MaterialTheme.typography.titleMedium)
                    OutlinedButton(
                        onClick = { permission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) },
                        modifier = wide,
                    ) { Text(stringResource(R.string.emergency_allow)) }
                }
                here == null -> Text(stringResource(R.string.emergency_waiting), style = MaterialTheme.typography.titleMedium)
                else -> {
                    val position = LatLon(here.latitude, here.longitude, LocationRepository.altitude(here))
                    SelectionContainer {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Big(stringResource(R.string.emergency_coordinates), decimal(position))
                            Fact(stringResource(R.string.emergency_dms), UnitFormat.coordinates(position.lat, position.lon, CoordinateFormat.DegreesMinutesSeconds))
                            GridRefs.utm(position)?.let { Fact(stringResource(R.string.emergency_utm), it) }
                            Fact(stringResource(R.string.emergency_plus_code), GridRefs.plusCode(position))
                            position.ele?.let { Fact(stringResource(R.string.emergency_altitude), Format.elevation(it)) }
                            if (here.hasAccuracy()) {
                                Fact(stringResource(R.string.emergency_accuracy), "± " + Format.shortDistance(here.accuracy.toDouble()))
                            }
                            Fact(stringResource(R.string.emergency_time), DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(here.time)))
                        }
                    }
                    val message = message(context, position, here)
                    if (smsButton) {
                        Button(onClick = { sms(context, smsTo, message) }, modifier = wide) {
                            Text(if (smsTo.isEmpty()) stringResource(R.string.emergency_sms) else stringResource(R.string.emergency_sms_to, contactName.ifBlank { smsTo }))
                        }
                    }
                    OutlinedButton(onClick = { share(context, message) }, modifier = wide) { Text(stringResource(R.string.emergency_share)) }
                    OutlinedButton(onClick = { copy(context, message) }, modifier = wide) { Text(stringResource(R.string.emergency_copy)) }
                }
            }
            Text(stringResource(R.string.emergency_hint), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 16.dp))
        }
    }
}

@Composable
private fun Big(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Text(value, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun Fact(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Medium)
    }
}

private fun decimal(p: LatLon) = String.format(Locale.ROOT, "%.5f, %.5f", p.lat, p.lon)

/** Everything a rescuer needs, readable without any app. */
private fun message(context: Context, p: LatLon, fix: Location): String {
    val lat = String.format(Locale.ROOT, "%.5f", p.lat)
    val lon = String.format(Locale.ROOT, "%.5f", p.lon)
    return listOfNotNull(
        context.getString(R.string.emergency_message_position, decimal(p)),
        fix.accuracy.takeIf { fix.hasAccuracy() }?.let { "± " + Format.shortDistance(it.toDouble()) },
        p.ele?.let { context.getString(R.string.emergency_message_altitude, Format.elevation(it)) },
        GridRefs.utm(p)?.let { "UTM $it" },
        "Plus Code ${GridRefs.plusCode(p)}",
        "https://www.openstreetmap.org/?mlat=$lat&mlon=$lon#map=16/$lat/$lon",
        context.getString(R.string.emergency_message_time, DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(fix.time))),
    ).joinToString("\n")
}

/** Opens the dialer with the number typed in: the user still presses call, and no permission is needed. */
private fun dial(context: Context, number: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(number)))) }
        .onFailure { Toast.makeText(context, R.string.emergency_no_app, Toast.LENGTH_LONG).show() }
}

/** An SMS with the position written in: works with no data, only a phone signal. */
private fun sms(context: Context, to: String, text: String) {
    val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(to))).putExtra("sms_body", text)
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, R.string.emergency_no_app, Toast.LENGTH_LONG).show()
    }
}

private fun share(context: Context, text: String) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    context.startActivity(Intent.createChooser(send, context.getString(R.string.emergency_title)))
}

private fun copy(context: Context, text: String) {
    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(text, text))
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) Toast.makeText(context, R.string.gps_copied, Toast.LENGTH_SHORT).show()
}
