package app.sentrek.ui

import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.sentrek.core.ui.R

/**
 * An action that asks "are you sure?" first, for things a stray tap shouldn't do: stopping
 * navigation, deleting downloads. [destructive] paints the button in the error colour.
 */
class Confirmation(
    val title: String,
    val text: String?,
    val confirmLabel: String,
    val destructive: Boolean = false,
    val onConfirm: () -> Unit,
)

/** Shows [confirmation]; [onDismiss] runs after either answer. */
@Composable
fun ConfirmDialog(confirmation: Confirmation, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(confirmation.title) },
        text = confirmation.text?.let { { Text(it) } },
        confirmButton = {
            Button(
                onClick = {
                    onDismiss()
                    confirmation.onConfirm()
                },
                colors = if (confirmation.destructive) {
                    ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                } else {
                    ButtonDefaults.buttonColors()
                },
                modifier = Modifier.heightIn(min = TouchTarget),
            ) { Text(confirmation.confirmLabel) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = TouchTarget)) {
                Text(stringResource(R.string.ui_cancel))
            }
        },
    )
}
