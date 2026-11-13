package app.sentrek.update

/** Version names and the release file for this phone's build. */
object Versions {
    /** "v0.27.0" or "0.27.0" as numbers; null for anything else. */
    fun parse(name: String): List<Int>? {
        val parts = name.trim().removePrefix("v").split('.')
        return parts.map { it.toIntOrNull()?.takeIf { n -> n >= 0 } ?: return null }
    }

    /** Whether [candidate] comes after [current]: 0.27.0 after 0.26.3, 0.26.10 after 0.26.9. */
    fun isNewer(candidate: String, current: String): Boolean {
        val a = parse(candidate) ?: return false
        val b = parse(current) ?: return false
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    /**
     * The release file with the same CPU type as the installed APK, from the last digit of its
     * version code (see app/build.gradle.kts): Sentrek-0.27.0-arm64.apk.
     */
    fun assetName(version: String, installedCode: Long): String? {
        val cpu = when ((installedCode % 10).toInt()) {
            0 -> "universal"
            1 -> "armv7"
            2 -> "arm64"
            4 -> "x86_64"
            else -> return null
        }
        return "Sentrek-${version.trim().removePrefix("v")}-$cpu.apk"
    }

    /**
     * What a release changes, from its notes: the list it starts with ("- …"), as written,
     * without the markdown marks. The paragraphs after it (how to install) are left out.
     */
    fun changes(notes: String?): List<String> = notes.orEmpty().lines()
        .map { it.trim() }
        .filter { it.startsWith("- ") || it.startsWith("* ") }
        .map { it.drop(2).replace("**", "").replace("`", "").trim() }
        .filter { it.isNotEmpty() }
}
