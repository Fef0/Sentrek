package app.sentrek.geo.route

import org.junit.Assert.assertEquals
import org.junit.Test

class ProfileMathTest {
    // Flat for 500 m, up 100 m over the next 100 m (45°), then down 50 m over 500 m.
    private val profile = listOf(0.0 to 1000.0, 500.0 to 1000.0, 600.0 to 1100.0, 1100.0 to 1050.0)

    @Test
    fun `height between points`() {
        assertEquals(1000.0, ProfileMath.heightAt(profile, 250.0), 1e-9)
        assertEquals(1050.0, ProfileMath.heightAt(profile, 550.0), 1e-9)
        assertEquals(1000.0, ProfileMath.heightAt(profile, -10.0), 1e-9)
        assertEquals(1050.0, ProfileMath.heightAt(profile, 5000.0), 1e-9)
    }

    @Test
    fun `slope in degrees, uphill positive`() {
        assertEquals(0.0, ProfileMath.slopeDegrees(profile, 200.0), 1e-9)
        assertEquals(45.0, ProfileMath.slopeDegrees(profile, 550.0), 1e-9)
        // 50 m down over 500 m: about -5.7°.
        assertEquals(-5.71, ProfileMath.slopeDegrees(profile, 850.0), 0.01)
        // At the very start the stretch is one-sided, not empty.
        assertEquals(0.0, ProfileMath.slopeDegrees(profile, 0.0), 1e-9)
        assertEquals(0.0, ProfileMath.slopeDegrees(listOf(0.0 to 10.0, 0.5 to 20.0), 0.2), 1e-9)
    }
}
