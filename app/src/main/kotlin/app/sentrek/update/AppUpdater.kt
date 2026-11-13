package app.sentrek.update

import android.app.Activity
import kotlinx.coroutines.flow.StateFlow

/**
 * New versions of the app from the GitHub releases: found now and then, said with a
 * notification, and installed only when the user says so, through Android's installer.
 * Only in the "github" build: F-Droid updates its own.
 */
interface AppUpdater {
    /** False in builds without an updater: its settings are hidden. */
    val available: Boolean

    /** A newer version that is out, until it is installed. */
    val newVersion: StateFlow<NewVersion?>

    /** Where an update or a check the user asked for is at. */
    val check: StateFlow<UpdateCheck>

    /** At app start: checks now and then, as the settings allow. */
    fun start()

    /** The user said update: downloads the new version and opens Android's installer. */
    fun update(activity: Activity, overMobileData: Boolean = false)

    /** "Check for updates": looks now. */
    fun checkNow()
}

/** A version newer than this one, and what it changes (the release's list), when it says. */
data class NewVersion(val version: String, val changes: List<String> = emptyList())

/** An update or a check the user asked for, step by step. */
sealed interface UpdateCheck {
    data object Idle : UpdateCheck
    data object Looking : UpdateCheck
    data class Downloading(val version: String, val fraction: Float) : UpdateCheck

    /** Downloaded: Android's installer is open. */
    data class Installing(val version: String) : UpdateCheck
    data class UpToDate(val version: String) : UpdateCheck

    /** A new version, and the phone on mobile data the user hasn't allowed: asked first. */
    data class OnMobileData(val version: String) : UpdateCheck

    /** A recording or a route is on: installing would close the app. */
    data class AfterHike(val version: String) : UpdateCheck
    data object Failed : UpdateCheck

    val busy: Boolean
        get() = this is Looking || this is Downloading
}
