package app.sentrek.geo.search

import java.text.Normalizer
import java.util.Locale

/**
 * Forgiving text search for names typed with gloves or without accents: "rifugio citta"
 * finds "Rifugio Città di Busto". Every word of the query must appear; a name that starts
 * with it ranks first, then one with a word that does, then any other match.
 */
object TextMatch {
    /** Lower case, without accents, punctuation as spaces. */
    fun normalize(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFD)
            .replace(MARKS, "")
            .lowercase(Locale.ROOT)
            .replace(SEPARATORS, " ")
            .trim()

    /** The query's words, normalized; empty when there is nothing to search for. */
    fun words(query: String): List<String> = normalize(query).split(' ').filter { it.isNotEmpty() }

    /** Lower is better; null when [text] doesn't match. [text] must be [normalize]d. */
    fun score(text: String, words: List<String>): Int? {
        if (words.isEmpty()) return null
        if (words.any { it !in text }) return null
        val first = words.first()
        return when {
            text.startsWith(first) -> 0
            text.contains(" $first") -> 1
            else -> 2
        }
    }

    private val MARKS = Regex("\\p{Mn}+")
    private val SEPARATORS = Regex("[^\\p{L}\\p{N}]+")
}
