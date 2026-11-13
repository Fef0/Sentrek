package app.sentrek.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The base style file has its own copy of some colours: the legend must show the same. */
class MapColorsTest {
    private val style = File("src/main/assets/styles/hiking.json").readText()

    /** The text of the layer [id] in the style file, up to the next layer. */
    private fun layer(id: String): String {
        val start = style.indexOf("\"id\": \"$id\"")
        assertTrue("no layer $id", start >= 0)
        val end = style.indexOf("\"id\":", start + 1).takeIf { it > 0 } ?: style.length
        return style.substring(start, end).replace(Regex("\\s+"), "")
    }

    @Test
    fun `base style colours match the legend's`() {
        assertTrue(layer("background").contains("\"background-color\":\"${MapColors.BACKGROUND}\""))
        assertTrue(layer("path").contains("\"line-color\":\"${MapColors.PATH}\""))
        assertTrue(layer("track").contains("\"line-color\":\"${MapColors.TRACK}\""))
        val route = layer("hiking-route")
        assertTrue(route.contains("[\"iwn\",\"nwn\"],\"${MapColors.ROUTE_NATIONAL}\""))
        assertTrue(route.contains("\"rwn\",\"${MapColors.ROUTE_REGIONAL}\""))
        assertTrue(route.contains("\"lwn\",\"${MapColors.ROUTE_LOCAL}\""))
        assertTrue(route.contains("\"line-opacity\":${MapColors.ROUTE_OPACITY}"))
    }

    @Test
    fun `hex colours become opaque argb`() {
        assertEquals(0xFFE6AF00.toInt(), MapColors.argb(MapColors.T1))
    }
}
