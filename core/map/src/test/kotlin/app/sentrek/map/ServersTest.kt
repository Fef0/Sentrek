package app.sentrek.map

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServersTest {
    @After
    fun reset() {
        ServerRoutes.custom = emptyMap()
    }

    @Test
    fun defaultsGoWhereTheyWere() {
        val tile = "https://tiles.openfreemap.org/planet/20261001/12/2131/1459.pbf"
        assertEquals(tile, ServerRoutes.rewrite(tile))
    }

    @Test
    fun tilesAndTerrainKeepTheirPathOnTheUsersServer() {
        ServerRoutes.custom = mapOf(
            Server.Tiles to "https://maps.example.org/ofm",
            Server.Terrain to "https://dem.example.org/terrarium/",
        )
        assertEquals(
            "https://maps.example.org/ofm/fonts/Noto%20Sans%20Bold/0-255.pbf",
            ServerRoutes.rewrite("https://tiles.openfreemap.org/fonts/Noto%20Sans%20Bold/0-255.pbf"),
        )
        assertEquals(
            "https://dem.example.org/terrarium/12/2131/1459.png",
            ServerRoutes.rewrite("https://s3.amazonaws.com/elevation-tiles-prod/terrarium/12/2131/1459.png"),
        )
        // Other servers are left alone.
        assertEquals("https://www.openstreetmap.org/node/1", ServerRoutes.rewrite("https://www.openstreetmap.org/node/1"))
    }

    @Test
    fun oneOverpassTakesThePlaceOfAllThree() {
        ServerRoutes.custom = mapOf(Server.Trails to "https://overpass.example.org/api/interpreter")
        for (default in Server.Trails.defaults) {
            assertEquals("https://overpass.example.org/api/interpreter", ServerRoutes.rewrite(default))
        }
    }

    @Test
    fun onlyHttpsAddresses() {
        assertTrue(Server.isValid("https://my.host/"))
        assertFalse(Server.isValid("http://my.host/"))
        assertFalse(Server.isValid("https://"))
        assertFalse(Server.isValid("https://my host/"))
    }
}
