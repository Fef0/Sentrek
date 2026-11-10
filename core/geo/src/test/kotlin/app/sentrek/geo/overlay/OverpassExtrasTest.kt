package app.sentrek.geo.overlay

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.StringReader
import java.io.StringWriter

class OverpassExtrasTest {
    private fun convert(kind: MapKind, json: String): List<JsonObject> {
        val out = StringWriter()
        OverpassExtras.convert(kind, StringReader(json), out, toleranceM = 0.0)
        return JsonParser.parseString(out.toString()).asJsonObject.getAsJsonArray("features").map { it.asJsonObject }
    }

    private fun JsonObject.str(key: String) = get(key).asString

    @Test
    fun `pistes come from their ways and from the routes they are part of`() {
        val json = """{"elements":[
            {"type":"relation","id":7,"members":[{"type":"way","ref":1,"role":""},{"type":"way","ref":2,"role":""}],
             "tags":{"route":"piste","piste:type":"nordic","name":"Anello del lago"}},
            {"type":"piste","id":1,"geometry":{"type":"LineString","coordinates":[[7.0,46.0],[7.01,46.01]]},
             "tags":{"pt":"downhill","pd":"intermediate","pr":"12"}},
            {"type":"piste","id":2,"geometry":{"type":"LineString","coordinates":[[7.0,46.0],[7.02,46.0]]},"tags":{}},
            {"type":"piste","id":3,"geometry":{"type":"LineString","coordinates":[[7.0,46.0],[7.03,46.0]]},"tags":{}}
        ]}"""
        val features = convert(MapKind.Winter, json)
        val props = features.map { it.getAsJsonObject("properties") }
        // Way 1: its own downhill piste, and the nordic loop on it, with the way's number.
        assertEquals(listOf("downhill", "nordic", "nordic"), props.map { it.str("pt") })
        assertEquals("intermediate", props[0].str("pd"))
        assertEquals("12", props[0].str("n"))
        assertEquals("12", props[1].str("n"))
        // Way 2 has no piste of its own: the route's name.
        assertEquals("Anello del lago", props[2].str("n"))
        assertEquals("way/2", props[2].str("id"))
        // Way 3 is neither (an untagged member of nothing): left out.
        assertEquals(3, features.size)
    }

    @Test
    fun `piste areas are polygons`() {
        val json = """{"elements":[
            {"type":"piste","id":4,"geometry":{"type":"Polygon","coordinates":[[[7.0,46.0],[7.01,46.0],[7.01,46.01],[7.0,46.0]],[[7.002,46.002],[7.003,46.002],[7.003,46.003],[7.002,46.002]]]},
             "tags":{"pt":"downhill","pd":"easy","ar":"yes"}},
            {"type":"piste","id":5,"geometry":{"type":"LineString","coordinates":[[7.0,46.0],[7.01,46.0],[7.01,46.01],[7.0,46.0]]},
             "tags":{"pt":"downhill","ar":"yes"}}
        ]}"""
        val features = convert(MapKind.Winter, json)
        assertEquals(listOf("Polygon", "Polygon"), features.map { it.getAsJsonObject("geometry").str("type") })
        // The outer ring only.
        assertEquals(4, features[0].getAsJsonObject("geometry").getAsJsonArray("coordinates")[0].asJsonArray.size())
        assertEquals(1, features[0].getAsJsonObject("properties").get("a").asInt)
    }

    @Test
    fun `cycle routes keep their network, number and name`() {
        val json = """{"elements":[
            {"type":"relation","id":10,"members":[{"type":"way","ref":1,"role":""}],
             "tags":{"route":"bicycle","network":"icn","ref":"EV5","name":"Via Romea Francigena"}},
            {"type":"relation","id":11,"members":[{"type":"way","ref":1,"role":""}],
             "tags":{"route":"mtb","network":"ncn","ref":"1","name":"Giro del Rosa"}},
            {"type":"relation","id":12,"members":[{"type":"way","ref":1,"role":""}],"tags":{"route":"bicycle"}},
            {"type":"bike","id":1,"geometry":{"type":"LineString","coordinates":[[7.0,46.0],[7.01,46.01]]},"tags":{}},
            {"type":"bike","id":2,"geometry":{"type":"LineString","coordinates":[[7.0,46.0],[7.01,46.01]]},"tags":{}}
        ]}"""
        val props = convert(MapKind.Cycling, json).map { it.getAsJsonObject("properties") }
        assertEquals(listOf("icn", "mtb"), props.map { it.str(OverpassExtras.NETWORK) })
        assertEquals(listOf(4, 1), props.map { it.get("w").asInt })
        assertEquals("EV5", props[0].str("ref"))
        assertEquals("Giro del Rosa", props[1].str("n"))
        assertEquals(10L, props[0].get("rid").asLong)
        assertFalse(props.any { it.get("rid")?.asLong == 12L })
    }
}
