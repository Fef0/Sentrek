package app.sentrek.geo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GridRefsTest {
    @Test
    fun plusCodeOfGoogleZurich() {
        assertEquals("8FVC9G8F+6X", GridRefs.plusCode(LatLon(47.365590, 8.524997)))
    }

    @Test
    fun plusCodeSouthWest() {
        // Worked by hand: 56.143125° × 8000 = 449145 and 331.215375° × 8000 = 2649723, in base 20.
        assertEquals("4RRH46V8+75", GridRefs.plusCode(LatLon(-33.856875, 151.215375)))
    }

    @Test
    fun utmOnCentralMeridian() {
        assertEquals("31N 500000 0", GridRefs.utm(LatLon(0.0, 3.0)))
        // Meridian arc to 45° is 4,984,944.4 m, scaled by 0.9996.
        assertEquals("32T 500000 4982950", GridRefs.utm(LatLon(45.0, 9.0)))
    }

    @Test
    fun utmSouthernHemisphereAndNorway() {
        // South of the equator, northings count down from 10,000 km.
        assertEquals("33H 500000 623", GridRefs.utm(LatLon(-34.0, 15.0))!!.take(14))
        // Bergen sits in the widened zone 32V.
        assertEquals('V', GridRefs.utm(LatLon(60.39, 5.32))!!.let { it[2] })
        assertEquals("32", GridRefs.utm(LatLon(60.39, 5.32))!!.take(2))
    }

    @Test
    fun noUtmNearThePoles() {
        assertNull(GridRefs.utm(LatLon(85.0, 0.0)))
    }
}
