package app.sentrek.data

import org.junit.Assert.assertEquals
import org.junit.Test

class PowerModeTest {
    @Test
    fun `frame cap per mode`() {
        assertEquals(0, PowerMode.Normal.mapFps(0))
        assertEquals(60, PowerMode.Normal.mapFps(60))
        // Battery saver caps at 30, but a lower choice stands.
        assertEquals(30, PowerMode.Saver.mapFps(0))
        assertEquals(30, PowerMode.Saver.mapFps(60))
        assertEquals(10, PowerMode.Saver.mapFps(10))
        assertEquals(10, PowerMode.Stamina.mapFps(60))
    }

    @Test
    fun `gps timing per mode`() {
        val chosen = GpsTiming(screenMs = 1_000, followMs = 10_000, recordMs = 2_000)
        assertEquals(chosen, chosen.forMode(PowerMode.Normal))
        // Battery saver only ever slows down: the 10 s chosen for following stays.
        assertEquals(GpsTiming(2_000, 10_000, 10_000), chosen.forMode(PowerMode.Saver))
        assertEquals(GpsTiming.Stamina, chosen.forMode(PowerMode.Stamina))
    }

    @Test
    fun `only stamina mode forces black screens`() {
        assertEquals(false, PowerMode.Normal.blackScreens)
        assertEquals(false, PowerMode.Saver.blackScreens)
        assertEquals(true, PowerMode.Stamina.blackScreens)
    }
}
