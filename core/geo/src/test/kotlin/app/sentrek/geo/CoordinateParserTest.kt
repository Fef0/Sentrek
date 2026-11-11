package app.sentrek.geo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CoordinateParserTest {
    private fun assertAt(lat: Double, lon: Double, text: String) {
        val p = CoordinateParser.parse(text) ?: throw AssertionError("Not read: $text")
        assertEquals(text, lat, p.lat, 1e-5)
        assertEquals(text, lon, p.lon, 1e-5)
    }

    @Test
    fun `decimal degrees`() {
        assertAt(37.74, 14.94, "37.74, 14.94")
        assertAt(37.74, 14.94, "37.74 14.94")
        assertAt(37.74, 14.94, " 37.74;14.94 ")
        assertAt(-33.92, -70.5, "-33.92, -70.5")
        assertAt(37.74, 14.94, "37.74N 14.94E")
        assertAt(37.74, 14.94, "N 37.74 E 14.94")
        assertAt(37.74, 14.94, "14.94E 37.74N")
        assertAt(-33.92, 18.42, "33.92 S, 18.42 E")
        assertAt(37.74, 14.94, "37.74° N, 14.94° E")
        assertAt(46.0, 7.0, "46°, 7°")
    }

    @Test
    fun `degrees minutes and seconds, as the position texts write them`() {
        assertAt(37.74, 14.94, "37° 44' 24.0\" N, 14° 56' 24.0\" E")
        assertAt(37.74, 14.94, "37°44'24\"N 14°56'24\"E")
        assertAt(37.74, 14.94, "37° 44.4' N 14° 56.4' E")
        assertAt(45.976389, 7.658333, "45°58′35″N 7°39′30″E")
    }

    @Test
    fun `geo links`() {
        assertAt(37.74, 14.94, "geo:37.74,14.94")
        assertAt(37.74, 14.94, "geo:37.74,14.94?z=15")
    }

    @Test
    fun `names and plain numbers are not positions`() {
        for (text in listOf("", "Rifugio", "Strada 12, 5", "12 5", "708", "Sentiero 708 Etna", "37.74", "1.2 3.4 5.6", "95.0, 14.0", "37.0, 190.0")) {
            assertNull(text, CoordinateParser.parse(text))
        }
    }
}
