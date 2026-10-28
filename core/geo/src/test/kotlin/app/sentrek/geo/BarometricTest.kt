package app.sentrek.geo

import org.junit.Assert.assertEquals
import org.junit.Test

class BarometricTest {
    @Test
    fun standardAtmosphere() {
        assertEquals(0.0, Barometric.altitude(Barometric.SEA_LEVEL_HPA), 0.01)
        // Standard tables: 898.76 hPa at 1000 m, 701.21 hPa at 3000 m.
        assertEquals(1000.0, Barometric.altitude(898.76), 2.0)
        assertEquals(3000.0, Barometric.altitude(701.21), 5.0)
    }

    @Test
    fun offsetCalibratesToAKnownHeight() {
        val offset = Barometric.offset(800.0, knownMetres = 2_100.0)
        assertEquals(2_100.0, Barometric.altitude(800.0) + offset, 1e-9)
        // About 8 m per hPa up here: a few hPa less is a climb.
        assertEquals(2_100.0 + 10 * 9.7, Barometric.altitude(790.0) + offset, 10.0)
    }
}
