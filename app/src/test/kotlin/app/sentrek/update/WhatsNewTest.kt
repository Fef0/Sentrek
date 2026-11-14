package app.sentrek.update

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class WhatsNewTest {
    @Test
    fun `changelog lines lose their bullets`() {
        assertEquals(
            listOf("Hold the start point", "Sections on saved routes"),
            WhatsNew.lines("• Hold the start point\n\n- Sections on saved routes\n"),
        )
    }

    /** The app shows the store changelog of its own version: each version must have one, in each language. */
    @Test
    fun `this version has a changelog in every language`() {
        val code = File("build.gradle.kts").readText().let { Regex("""versionCode = (\d+)""").find(it)!!.groupValues[1].toInt() }
        for (locale in File("../fastlane/metadata/android").listFiles()!!.filter { it.isDirectory }) {
            val file = locale.resolve("changelogs/${code * 10 + 4}.txt")
            assert(file.isFile) { "no changelog $file" }
            assert(WhatsNew.lines(file.readText()).isNotEmpty()) { "empty changelog $file" }
        }
    }
}
