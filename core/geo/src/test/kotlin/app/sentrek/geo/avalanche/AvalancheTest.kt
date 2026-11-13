package app.sentrek.geo.avalanche

import app.sentrek.geo.PmTiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AvalancheTest {
    private fun bytes(name: String) = javaClass.getResourceAsStream("/avalanche/$name")!!.use { it.readBytes() }

    @Test
    fun `finds the tile of a point in the regions file`() {
        val header = PmTiles.header(bytes("eaws-head.bin"))
        assertEquals(10, header.maxZoom)
        val root = PmTiles.directory(
            bytes("eaws-head.bin").copyOfRange(header.rootOffset.toInt(), (header.rootOffset + header.rootLength).toInt()),
            header.internalCompression,
        )
        // Bormio.
        val (x, y) = AvalancheRegions.tile(46.4675, 10.3707)
        assertEquals(541 to 362, x to y)
        val entry = PmTiles.find(root, PmTiles.tileId(AvalancheRegions.ZOOM, x, y))
        assertNotNull(entry)
        assertEquals(14162, entry!!.length)
        assertEquals(7496025L, entry.offset)
    }

    @Test
    fun `tile ids follow the hilbert curve`() {
        assertEquals(0L, PmTiles.tileId(0, 0, 0))
        assertEquals(1L, PmTiles.tileId(1, 0, 0))
        assertEquals(2L, PmTiles.tileId(1, 0, 1))
        assertEquals(3L, PmTiles.tileId(1, 1, 1))
        assertEquals(4L, PmTiles.tileId(1, 1, 0))
        assertEquals(5L, PmTiles.tileId(2, 0, 0))
    }

    @Test
    fun `picks the micro-region valid on the day, regional service first`() {
        val tile = bytes("eaws-10-541-362.mvt")
        assertEquals(listOf("IT-25-SO-03", "IT-MeteoMont-04-1"), AvalancheRegions.at(tile, 46.4675, 10.3707, "2026-11-13"))
    }

    @Test
    fun `service files are named after shorter ids`() {
        assertEquals(listOf("IT-25-SO", "IT-25", "IT"), AvalancheRegions.services("IT-25-SO-03"))
        assertEquals(listOf("CH"), AvalancheRegions.services("CH-1114"))
        assertEquals(
            "https://static.avalanche.report/eaws_bulletins/2026-04-01/2026-04-01-IT-25.it.json",
            AvalancheRegions.bulletinUrl("IT-25", "2026-04-01", "it"),
        )
        assertTrue(AvalancheRegions.bulletinUrl("CH", "2026-04-01", null).endsWith("/2026-04-01-CH.json"))
    }

    @Test
    fun `reads the bulletin of a region`() {
        val b = bytes("2026-04-01-IT-25.it.json").inputStream().reader().use { Bulletin.parse(it, "IT-25-SO-03") }
        assertNotNull(b)
        b!!
        assertEquals("AINEVA Lombardia", b.provider)
        assertEquals(3, b.maxLevel)
        assertEquals(
            listOf(
                Bulletin.Rating(1, Bulletin.Elevation(null, "treeline"), Bulletin.Period.AllDay),
                Bulletin.Rating(3, Bulletin.Elevation("treeline", null), Bulletin.Period.AllDay),
            ),
            b.ratings,
        )
        assertEquals("wind_slab", b.problems[0].type)
        // In compass order, whatever order the file has them in.
        assertEquals(Bulletin.ASPECTS, b.problems[0].aspects)
        assertEquals(Bulletin.Elevation("1900", null), b.problems[1].elevation)
        assertEquals(1774994400000L, b.validFrom)
        assertEquals(1775080800000L, b.validUntil)
        assertEquals("steady", b.tendency)
        assertTrue(b.highlights!!.isNotBlank())
        assertTrue(b.activity!!.contains('\n'))
        assertTrue(!b.activity!!.contains("<br"))
    }

    @Test
    fun `no bulletin for a region the file doesn't cover`() {
        assertNull(bytes("2026-04-01-IT-25.it.json").inputStream().reader().use { Bulletin.parse(it, "CH-1114") })
        assertNull(Bulletin.parse("""{"bulletins":[{"regions":[{"regionID":"X-1"}],"dangerRatings":[{"mainValue":"no_snow"}]}]}""".reader(), "X-1"))
    }

    @Test
    fun `html in the texts becomes plain text`() {
        assertEquals("One.\nTwo & three.", Bulletin.plain("One. <br/> Two &amp; <b>three</b>."))
        assertEquals("Snow cover\nSince Monday.\nMore.", Bulletin.plain("<h1>Snow cover</h1><p>Since Monday.</p><p>More.</p>"))
    }
}
