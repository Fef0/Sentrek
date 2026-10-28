package app.sentrek.geo.units

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class UnitFormatTest {
    private val en = Locale.ROOT

    @Test
    fun `distances in each unit`() {
        assertEquals("850 m", UnitFormat.distance(850.0, DistanceUnit.Kilometers, en))
        assertEquals("3.42 km", UnitFormat.distance(3_420.0, DistanceUnit.Kilometers, en))
        assertEquals("12.5 km", UnitFormat.distance(12_500.0, DistanceUnit.Kilometers, en))
        assertEquals("328 ft", UnitFormat.distance(100.0, DistanceUnit.Miles, en))
        assertEquals("2.00 mi", UnitFormat.distance(3_218.688, DistanceUnit.Miles, en))
        assertEquals("1.00 nmi", UnitFormat.distance(1_852.0, DistanceUnit.NauticalMiles, en))
        assertEquals("16 ft", UnitFormat.shortDistance(5.0, DistanceUnit.Miles))
        assertEquals("5 m", UnitFormat.shortDistance(5.0, DistanceUnit.NauticalMiles))
    }

    @Test
    fun `heights and speeds`() {
        assertEquals("1520 m", UnitFormat.elevation(1_520.0, ElevationUnit.Meters))
        assertEquals("4987 ft", UnitFormat.elevation(1_520.0, ElevationUnit.Feet))
        assertEquals("4.5 km/h", UnitFormat.speed(1.25, SpeedUnit.KilometersPerHour, en))
        assertEquals("18 km/h", UnitFormat.speed(5.0, SpeedUnit.KilometersPerHour, en))
        assertEquals("2.8 mph", UnitFormat.speed(1.25, SpeedUnit.MilesPerHour, en))
        assertEquals("9.7 kn", UnitFormat.speed(5.0, SpeedUnit.Knots, en))
    }

    @Test
    fun `slopes in degrees and percent`() {
        assertEquals("↗ 45°", UnitFormat.slope(45.0, SlopeUnit.Degrees))
        assertEquals("↘ 6°", UnitFormat.slope(-5.7, SlopeUnit.Degrees))
        assertEquals("→ 0°", UnitFormat.slope(0.3, SlopeUnit.Degrees))
        assertEquals("↗ 100%", UnitFormat.slope(45.0, SlopeUnit.Percent))
        assertEquals("↘ 10%", UnitFormat.slope(-5.71, SlopeUnit.Percent))
    }

    @Test
    fun `bearings with compass points`() {
        assertEquals("N 0°", UnitFormat.bearing(359.7, BearingUnit.Degrees))
        assertEquals("SW 225°", UnitFormat.bearing(225.0, BearingUnit.Degrees))
        assertEquals("E 1600 mil", UnitFormat.bearing(90.0, BearingUnit.Mils))
        assertEquals("NW 315°", UnitFormat.bearing(-45.0, BearingUnit.Degrees))
    }

    @Test
    fun `coordinates in each format`() {
        assertEquals("37.75000, -15.00000", UnitFormat.coordinates(37.75, -15.0, CoordinateFormat.Decimal))
        assertEquals("37° 45.000′ N, 15° 00.000′ W", UnitFormat.coordinates(37.75, -15.0, CoordinateFormat.DegreesMinutes))
        assertEquals("33° 51′ 35.9″ S, 151° 12′ 40.0″ E", UnitFormat.coordinates(-33.859972, 151.211111, CoordinateFormat.DegreesMinutesSeconds))
        // Rounding carries into the degrees.
        assertEquals("46° 00.000′ N, 8° 00.000′ E", UnitFormat.coordinates(45.9999999, 7.99999999, CoordinateFormat.DegreesMinutes))
    }

    @Test
    fun `scale bar lengths`() {
        assertEquals(500.0, UnitFormat.roundDown(730.0), 1e-9)
        assertEquals(2.0, UnitFormat.roundDown(2.0), 1e-9)
        assertEquals(0.1, UnitFormat.roundDown(0.19), 1e-9)
    }
}
