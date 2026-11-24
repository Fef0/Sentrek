package app.sentrek.data

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Closing the app ends it: back twice on the map, or a swipe away from the recent apps. The
 * services (feature modules) and the screen (app) ask for it here; the app does it (see Quit
 * in the app module): the recording stops and is saved, following stops, and the process ends.
 */
@Singleton
class AppExit @Inject constructor() {
    /** Set once by the app at start. */
    var onQuit: (() -> Unit)? = null

    fun quit() {
        onQuit?.invoke()
    }
}
