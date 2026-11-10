package app.sentrek.map

import app.sentrek.geo.LatLon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ArrowPartsTest {
    private val lat = 45.0
    private val mPerDegLon = 111_320.0 * kotlin.math.cos(Math.toRadians(lat))

    /** A line going east from [fromM] to [toM] metres, [northM] metres north of the base. */
    private fun east(fromM: Double, toM: Double, northM: Double = 0.0) = listOf(
        LatLon(lat + northM / 111_320.0, 9.0 + fromM / mPerDegLon),
        LatLon(lat + northM / 111_320.0, 9.0 + toM / mPerDegLon),
    )

    private fun lengthM(lines: List<List<LatLon>>) = lines.sumOf { l ->
        l.zipWithNext().sumOf { (a, b) -> kotlin.math.hypot((b.lon - a.lon) * mPerDegLon, (b.lat - a.lat) * 111_320.0) }
    }

    @Test
    fun `the top track keeps all its arrows`() {
        val parts = ArrowParts.free(listOf(listOf(east(0.0, 1000.0)), listOf(east(0.0, 1000.0, 5.0))))
        assertEquals(1000.0, lengthM(parts[1]), 1.0)
    }

    @Test
    fun `a track under another on the same trail has no arrows there`() {
        val parts = ArrowParts.free(listOf(listOf(east(0.0, 1000.0)), listOf(east(0.0, 1000.0, 5.0))))
        assertTrue(parts[0].isEmpty())
    }

    @Test
    fun `only the shared stretch loses its arrows`() {
        // The top one covers the first half of the bottom one.
        val parts = ArrowParts.free(listOf(listOf(east(0.0, 2000.0)), listOf(east(0.0, 1000.0, 8.0))))
        val free = lengthM(parts[0])
        assertTrue("free $free", free in 950.0..1000.0)
        assertTrue(parts[0].all { l -> l.all { (it.lon - 9.0) * mPerDegLon >= 1000.0 } })
    }

    @Test
    fun `tracks apart keep all their arrows`() {
        val parts = ArrowParts.free(listOf(listOf(east(0.0, 1000.0)), listOf(east(0.0, 1000.0, 200.0))))
        assertEquals(1000.0, lengthM(parts[0]), 1.0)
        assertEquals(1000.0, lengthM(parts[1]), 1.0)
    }

    @Test
    fun `a crossing only takes the arrows where they meet`() {
        val north = listOf(LatLon(lat - 500 / 111_320.0, 9.0 + 500 / mPerDegLon), LatLon(lat + 500 / 111_320.0, 9.0 + 500 / mPerDegLon))
        val parts = ArrowParts.free(listOf(listOf(east(0.0, 1000.0)), listOf(north)))
        assertEquals(950.0, lengthM(parts[0]), 20.0)
    }

    @Test
    fun `three on the same trail, only the top one`() {
        val parts = ArrowParts.free(listOf(listOf(east(0.0, 500.0)), listOf(east(0.0, 500.0, 3.0)), listOf(east(0.0, 500.0, 6.0))))
        assertTrue(parts[0].isEmpty())
        assertTrue(parts[1].isEmpty())
        assertEquals(500.0, lengthM(parts[2]), 1.0)
    }
}
