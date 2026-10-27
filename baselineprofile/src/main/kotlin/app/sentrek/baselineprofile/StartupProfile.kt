package app.sentrek.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Records the code a usual session runs (start-up, the map, the main screens) so release
 * builds ship it compiled ahead of time: faster start and smoother first frames on old phones.
 */
@RunWith(AndroidJUnit4::class)
class StartupProfile {
    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun startAndBrowse() = rule.collect(packageName = "app.sentrek", includeInStartupProfile = true) {
        pressHome()
        startActivityAndWait()
        device.waitForIdle()
        for (screen in listOf("Tracks", "Places", "Maps")) {
            device.wait(Until.findObject(By.text(screen)), 5_000)?.click() ?: continue
            device.waitForIdle()
            device.pressBack()
            device.waitForIdle()
        }
    }
}
