package app.sentrek.update

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.sentrek.R
import app.sentrek.ui.TouchTarget

/**
 * A new version is out: what it changes, and update now or later. Updating downloads it here
 * (with its progress), then Android's installer asks to confirm.
 */
@Composable
fun UpdateDialog(
    update: NewVersion,
    check: UpdateCheck,
    onUpdate: () -> Unit,
    onOverMobileData: () -> Unit,
    onLater: () -> Unit,
) {
    val context = LocalContext.current
    val asksPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()
    val downloading = check as? UpdateCheck.Downloading
    AlertDialog(
        onDismissRequest = onLater,
        title = { Text(stringResource(R.string.update_new_title, update.version)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (update.changes.isNotEmpty()) {
                    Text(stringResource(R.string.update_changes), style = MaterialTheme.typography.titleSmall)
                    for (change in update.changes) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("•", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                            Text(change, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                when {
                    downloading != null -> {
                        Text(stringResource(R.string.update_downloading, downloading.version, (downloading.fraction * 100).toInt()))
                        LinearProgressIndicator(progress = { downloading.fraction }, modifier = Modifier.fillMaxWidth())
                    }
                    check is UpdateCheck.OnMobileData -> Text(stringResource(R.string.update_on_mobile_data, update.version))
                    check is UpdateCheck.Failed -> Text(stringResource(R.string.update_download_failed), color = MaterialTheme.colorScheme.error)
                    check is UpdateCheck.AfterHike -> Text(stringResource(R.string.update_after_hike))
                    else -> {
                        Text(stringResource(R.string.update_new_text))
                        if (asksPermission) Text(stringResource(R.string.update_new_permission))
                    }
                }
            }
        },
        confirmButton = {
            when {
                downloading != null || check is UpdateCheck.Installing || check is UpdateCheck.AfterHike -> Unit
                check is UpdateCheck.OnMobileData -> Button(onClick = onOverMobileData, modifier = Modifier.heightIn(min = TouchTarget)) {
                    Text(stringResource(R.string.update_download_anyway))
                }
                else -> Button(onClick = onUpdate, modifier = Modifier.heightIn(min = TouchTarget)) {
                    Text(stringResource(R.string.update_now))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onLater, modifier = Modifier.heightIn(min = TouchTarget)) {
                Text(stringResource(R.string.update_later))
            }
        },
    )
}
