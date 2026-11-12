package app.sentrek.update

import android.app.Activity
import kotlinx.coroutines.flow.StateFlow

/**
 * New versions of the app from the GitHub releases, downloaded and installed by themselves.
 * Only in the "github" build: F-Droid updates its own.
 */
interface AppUpdater {
    /** False in builds without an updater: its settings are hidden. */
    val available: Boolean

    /** A version downloaded that can't install by itself and waits for the user to say so. */
    val waiting: StateFlow<String?>

    /** Where a check the user asked for is at. */
    val check: StateFlow<UpdateCheck>

    /** At app start: checks now and then, as the settings allow. */
    fun start()

    /** The user said install: asks Android for permission first if needed. */
    fun install(activity: Activity)

    /** "Check for updates": looks now, and downloads and installs a new version at once. */
    fun checkNow(overMobileData: Boolean = false)
}

/** A check the user asked for, step by step. */
sealed interface UpdateCheck {
    data object Idle : UpdateCheck
    data object Looking : UpdateCheck
    data class Downloading(val version: String, val fraction: Float) : UpdateCheck
    data class Installing(val version: String) : UpdateCheck
    data class UpToDate(val version: String) : UpdateCheck

    /** A new version, and the phone on mobile data the user hasn't allowed: asked first. */
    data class OnMobileData(val version: String) : UpdateCheck

    /** Downloaded, but a recording or a route is on: installing would close the app. */
    data class AfterHike(val version: String) : UpdateCheck
    data object Failed : UpdateCheck

    val busy: Boolean
        get() = this is Looking || this is Downloading || this is Installing
}
