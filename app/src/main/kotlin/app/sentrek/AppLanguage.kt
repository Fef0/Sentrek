package app.sentrek

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import java.util.Locale

/**
 * The app's language: the phone's (null), or one picked in Settings from [available]. From
 * Android 13 the system keeps the choice (and shows it in the phone's own app settings);
 * before, it's kept here and every screen and the app itself are put in it as they start.
 */
object AppLanguage {
    /** Language tags the app is translated into, from its resource folders (see TRANSLATING.md). */
    val available: List<String> = BuildConfig.LANGUAGES.toList()

    /** The picked language's tag, or null for the phone's. */
    fun current(context: Context): String? {
        val tag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.getSystemService(LocaleManager::class.java).applicationLocales.get(0)?.toLanguageTag()
        } else {
            prefs(context).getString(KEY, null)
        }
        return tag?.let(::match)
    }

    /** Switches to [tag] (null: the phone's language); [activity] is drawn again in it. */
    fun set(activity: Activity, tag: String?) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // The system recreates the screens by itself.
            activity.getSystemService(LocaleManager::class.java).applicationLocales =
                LocaleList.forLanguageTags(tag.orEmpty())
            return
        }
        prefs(activity).edit().putString(KEY, tag).commit()
        applyToApp(activity.applicationContext)
        activity.recreate()
    }

    /** A language under its own name, as its speakers write it: "Italiano", "Deutsch". */
    fun name(tag: String): String {
        val locale = Locale.forLanguageTag(tag)
        return locale.getDisplayName(locale).replaceFirstChar { it.titlecase(locale) }
    }

    /** Before Android 13: a screen's context in the chosen language. */
    fun wrap(base: Context): Context {
        val locale = legacyLocale(base) ?: return base
        val config = Configuration(base.resources.configuration).apply { setLocale(locale) }
        return base.createConfigurationContext(config)
    }

    /** Before Android 13: the app's own resources (notifications, services) in the chosen language. */
    @Suppress("DEPRECATION")
    fun applyToApp(app: Context) {
        val locale = legacyLocale(app) ?: return
        Locale.setDefault(locale)
        val config = Configuration(app.resources.configuration).apply { setLocale(locale) }
        app.resources.updateConfiguration(config, app.resources.displayMetrics)
    }

    /** One of [available] for [tag]: exactly, or by its language alone ("it-IT" is "it"). */
    private fun match(tag: String): String? =
        available.firstOrNull { it.equals(tag, ignoreCase = true) }
            ?: available.firstOrNull { it.equals(Locale.forLanguageTag(tag).language, ignoreCase = true) }

    private fun legacyLocale(context: Context): Locale? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return null
        return prefs(context).getString(KEY, null)?.let(Locale::forLanguageTag)
    }

    private fun prefs(context: Context) = context.getSharedPreferences("language", Context.MODE_PRIVATE)

    private const val KEY = "tag"
}
