package app.sentrek.geo.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TextMatchTest {
    private fun score(name: String, query: String) = TextMatch.score(TextMatch.normalize(name), TextMatch.words(query))

    @Test
    fun ignoresAccentsCaseAndPunctuation() {
        assertEquals("rifugio citta di busto", TextMatch.normalize("Rifugio Città-di Busto!"))
        assertEquals(0, score("Rifugio Città di Busto", "RIFUGIO citta"))
    }

    @Test
    fun everyWordMustAppear() {
        assertEquals(1, score("Lago di Como", "como lago"))
        assertNull(score("Lago di Como", "lago garda"))
    }

    @Test
    fun ranksStartThenWordThenAnywhere() {
        assertEquals(0, score("Monte Rosa", "mon"))
        assertEquals(1, score("Punta Monte", "mon"))
        assertEquals(2, score("Piemonte", "mon"))
    }

    @Test
    fun emptyQueryMatchesNothing() {
        assertNull(score("Anything", "  ,  "))
    }
}
