package app.sentrek.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionsTest {
    @Test
    fun newer() {
        assertTrue(Versions.isNewer("v0.27.0", "0.26.0"))
        assertTrue(Versions.isNewer("0.26.10", "0.26.9"))
        assertTrue(Versions.isNewer("1.0", "0.99.99"))
        assertTrue(Versions.isNewer("0.26.0.1", "0.26.0"))
    }

    @Test
    fun notNewer() {
        assertFalse(Versions.isNewer("v0.26.0", "0.26.0"))
        assertFalse(Versions.isNewer("0.26", "0.26.0"))
        assertFalse(Versions.isNewer("0.25.9", "0.26.0"))
        assertFalse(Versions.isNewer("v0.27.0-beta", "0.26.0"))
        assertFalse(Versions.isNewer("", "0.26.0"))
    }

    @Test
    fun assetForEachCpu() {
        assertEquals("Sentrek-0.27.0-universal.apk", Versions.assetName("v0.27.0", 320))
        assertEquals("Sentrek-0.27.0-armv7.apk", Versions.assetName("v0.27.0", 321))
        assertEquals("Sentrek-0.27.0-arm64.apk", Versions.assetName("0.27.0", 322))
        assertEquals("Sentrek-0.27.0-x86_64.apk", Versions.assetName("v0.27.0", 324))
        assertNull(Versions.assetName("v0.27.0", 323))
    }

    @Test
    fun `changes are the release's list`() {
        val notes = "- Parks are named on the map.\n- Planner: the **flag** button.\n\nIf you have 0.27, it updates itself. `arm64` is for phones."
        assertEquals(listOf("Parks are named on the map.", "Planner: the flag button."), Versions.changes(notes))
        assertEquals(emptyList<String>(), Versions.changes(null))
    }
}
