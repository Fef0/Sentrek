package app.sentrek.update

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.sentrek.R
import app.sentrek.ui.TouchTarget

/** A downloaded version that can't install by itself: install now, or later. */
@Composable
fun UpdateDialog(version: String, onInstall: () -> Unit, onLater: () -> Unit) {
    val context = LocalContext.current
    // From Android 12, allowed once, the next ones install on their own.
    val asksPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !context.packageManager.canRequestPackageInstalls()
    AlertDialog(
        onDismissRequest = onLater,
        title = { Text(stringResource(R.string.update_ready_title, version)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.update_ready_text))
                if (asksPermission) Text(stringResource(R.string.update_ready_permission))
            }
        },
        confirmButton = {
            Button(onClick = onInstall, modifier = Modifier.heightIn(min = TouchTarget)) {
                Text(stringResource(R.string.update_install))
            }
        },
        dismissButton = {
            TextButton(onClick = onLater, modifier = Modifier.heightIn(min = TouchTarget)) {
                Text(stringResource(R.string.update_later))
            }
        },
    )
}
