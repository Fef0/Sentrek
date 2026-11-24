package app.sentrek

import android.os.SystemClock
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext

/**
 * Back on the map leaves the app, by [onExit]. With [confirm], the first back says "press
 * again" ([message]) and only a second one within a couple of seconds leaves. Registered
 * before the screens' own back handlers, so closing a sheet or the planner still comes first.
 */
@Composable
fun ExitConfirmation(enabled: Boolean, confirm: Boolean, message: Int, onExit: () -> Unit) {
    val context = LocalContext.current
    val dispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher ?: return
    val ask by rememberUpdatedState(confirm)
    val text by rememberUpdatedState(message)
    val exit by rememberUpdatedState(onExit)
    val callback = remember(dispatcher) {
        object : OnBackPressedCallback(enabled) {
            private var firstPressAt = 0L

            override fun handleOnBackPressed() {
                val now = SystemClock.uptimeMillis()
                if (!ask || now - firstPressAt < EXIT_WINDOW_MS) {
                    exit()
                } else {
                    firstPressAt = now
                    Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    SideEffect { callback.isEnabled = enabled }
    DisposableEffect(callback) {
        dispatcher.addCallback(callback)
        onDispose { callback.remove() }
    }
}

/** About as long as the "press again" message stays up. */
private const val EXIT_WINDOW_MS = 2_000L
