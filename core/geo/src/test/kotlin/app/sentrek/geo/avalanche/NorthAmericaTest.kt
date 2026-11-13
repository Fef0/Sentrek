package app.sentrek.geo.avalanche

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NorthAmericaTest {
    private fun reader(name: String) = javaClass.getResourceAsStream("/avalanche/$name")!!.reader()

    @Test
    fun `finds the canadian area of a point`() {
        val areas = GeoAreas.read(reader("ca-area-glacier.json"))
        // Rogers Pass: Avalanche Canada's own point lookup gives this area.
        assertEquals("b2a14eb464567902bcdf5b0cf1efe870416fee4de1d80d5b1f6d9cbc1b3bd73a", areas.at(51.30, -117.52)?.id)
        assertNull(areas.at(46.0, 7.0))
    }

    @Test
    fun `reads a canadian forecast by elevation band`() {
        val area = "b2a14eb464567902bcdf5b0cf1efe870416fee4de1d80d5b1f6d9cbc1b3bd73a"
        val (now, next) = AvalancheCanada.parse(reader("ca-products.json"), area)!!
        assertEquals("Avalanche Canada", now.provider)
        assertEquals(
            listOf(3 to Bulletin.Band.Alpine, 2 to Bulletin.Band.Treeline, 1 to Bulletin.Band.BelowTreeline),
            now.ratings.map { it.level to it.band },
        )
        assertEquals(3, now.maxLevel)
        val problem = now.problems.single()
        assertEquals("wind_slab", problem.type)
        assertEquals(listOf("N", "NE", "E", "NW"), problem.aspects)
        assertEquals(listOf(Bulletin.Band.Alpine, Bulletin.Band.Treeline), problem.bands)
        assertEquals("Avoid steep lee slopes.", now.highlights)
        assertEquals("A crust 60 cm down.", now.snowpack)
        // Today's forecast ends when tomorrow's day starts; "No Rating" is left out.
        assertEquals(now.validUntil, next!!.validFrom)
        assertEquals(listOf(4 to Bulletin.Band.Alpine, 2 to Bulletin.Band.BelowTreeline), next.ratings.map { it.level to it.band })
        assertNull(AvalancheCanada.parse(reader("ca-products.json"), "elsewhere"))
    }

    @Test
    fun `reads a us forecast for its zone`() {
        val zone = GeoAreas.read(reader("us-map-stevens.json")).areas.single()
        assertEquals("3024", zone.id)
        val (now, next) = AvalancheUs.parse(reader("us-forecast-nwac.json"), zone)!!
        assertEquals("Northwest Avalanche Center", now.provider)
        assertEquals("Stevens Pass", now.regionName)
        assertEquals(listOf(2, 2, 1), now.ratings.map { it.level })
        assertEquals(Bulletin.Band.Alpine, now.ratings.first().band)
        val problem = now.problems.single()
        assertEquals("loose_wet", problem.type)
        assertEquals(Bulletin.ASPECTS, problem.aspects)
        assertEquals(listOf(Bulletin.Band.Alpine, Bulletin.Band.Treeline), problem.bands)
        assertTrue(now.highlights!!.startsWith("On Sunday"))
        assertEquals(1769911200000L, now.validFrom)
        assertNotNull(next)
    }

    @Test
    fun `a us zone without its forecast file has the map's level`() {
        val zone = GeoAreas.read(reader("us-map-stevens.json")).areas.single()
        val (now, _) = AvalancheUs.parse(null, zone)!!
        assertEquals(2, now.maxLevel)
        assertNull(now.ratings.single().band)
    }

    @Test
    fun `levels and problem names`() {
        assertEquals(3, level("3:considerable"))
        assertEquals(3, level("considerable"))
        assertEquals(4, level("4 - High"))
        assertEquals(5, level("extreme"))
        assertNull(level("norating"))
        assertNull(level("earlyseason"))
        assertEquals("deep_persistent_slab", NorthAmerica.problemType("Deep Persistent Slab"))
        assertEquals("loose_dry", NorthAmerica.problemType("Dry Loose"))
        assertEquals("cornices", NorthAmerica.problemType("Cornice"))
        assertEquals("gliding_snow", NorthAmerica.problemType("Glide"))
    }
}
