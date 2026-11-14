package app.sentrek.update

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.sentrek.BuildConfig
import app.sentrek.R
import app.sentrek.ui.TouchTarget

/**
 * What this version changes, said once after an update: however it came (the app's own
 * updater, F-Droid, a file installed by hand). The list is the store changelog packed with the
 * app (see the whatsNew task in app/build.gradle.kts), in the app's language.
 */
object WhatsNew {
    private const val PREFS = "whats_new"
    private const val KEY_SHOWN = "shown"

    /**
     * This version's changes, if it was just updated to and they haven't been shown; null on a
     * first install (nothing was new to the user) or once seen.
     */
    fun pending(context: Context, language: String): List<String>? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val shown = prefs.getString(KEY_SHOWN, null)
        if (shown == BuildConfig.VERSION_NAME) return null
        // Versions before this one didn't keep the key: an install that was updated since is
        // an update too.
        val updated = shown != null || runCatching {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            info.lastUpdateTime != info.firstInstallTime
        }.getOrDefault(false)
        val changes = if (updated) read(context, language) else emptyList()
        if (changes.isEmpty()) {
            seen(context)
            return null
        }
        return changes
    }

    fun seen(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_SHOWN, BuildConfig.VERSION_NAME).apply()
    }

    /** The changelog in [language], or in English; one change per line, without the bullets. */
    private fun read(context: Context, language: String): List<String> {
        val text = listOf(language, "en").firstNotNullOfOrNull { lang ->
            runCatching { context.assets.open("whatsnew/$lang.txt").bufferedReader().use { it.readText() } }.getOrNull()
        } ?: return emptyList()
        return lines(text)
    }

    /** "• Something" lines as "Something"; blank lines left out. */
    fun lines(text: String): List<String> = text.lines()
        .map { it.trim().removePrefix("•").removePrefix("-").removePrefix("*").trim() }
        .filter { it.isNotEmpty() }
}

/** "What's new in Sentrek X": the list, and OK. */
@Composable
fun WhatsNewDialog(changes: List<String>, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.whats_new_title, BuildConfig.VERSION_NAME)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                for (change in changes) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("•", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
                        Text(change, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onDismiss, modifier = Modifier.heightIn(min = TouchTarget)) {
                Text(stringResource(R.string.whats_new_ok))
            }
        },
    )
}
