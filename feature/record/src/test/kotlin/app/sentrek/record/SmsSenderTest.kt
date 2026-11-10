package app.sentrek.record

import org.junit.Assert.assertEquals
import org.junit.Test

class SmsSenderTest {
    @Test
    fun `plain keeps the SMS alphabet`() {
        val text = "Posizione: 37.76550, 14.95100\nPiù su, c'è un rifugio: là!"
        assertEquals(text, SmsSender.plain(text))
    }

    @Test
    fun `plain swaps what the alphabet lacks`() {
        assertEquals("+/- 5 m", SmsSender.plain("± 5 m"))
        assertEquals("12:40 PM", SmsSender.plain("12:40 PM"))
        assertEquals("Malaga, Krakow", SmsSender.plain("Málaga, Kraków"))
        assertEquals("\"Monte\" - 45 deg", SmsSender.plain("“Monte” – 45°"))
    }
}
