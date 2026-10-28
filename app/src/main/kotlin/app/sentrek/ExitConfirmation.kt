package app.sentrek

import android.os.SystemClock
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * While [enabled], the first back says "press again" and only a second one within a couple of
 * seconds leaves the app. Registered before the screens' own back handlers, so closing a
 * sheet or the planner still comes first.
 */
@Composable
fun ExitConfirmation(enabled: Boolean) {
    val context = LocalContext.current
    val dispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher ?: return
    val callback = remember(dispatcher) {
        object : OnBackPressedCallback(enabled) {
            private var firstPressAt = 0L

            override fun handleOnBackPressed() {
                val now = SystemClock.uptimeMillis()
                if (now - firstPressAt < EXIT_WINDOW_MS) {
                    // Let the system do what back normally does on the last screen.
                    isEnabled = false
                    dispatcher.onBackPressed()
                    isEnabled = true
                } else {
                    firstPressAt = now
                    Toast.makeText(context, R.string.exit_press_again, Toast.LENGTH_SHORT).show()
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
