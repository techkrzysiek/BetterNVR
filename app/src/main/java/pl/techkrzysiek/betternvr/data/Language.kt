package pl.techkrzysiek.betternvr.data

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import java.util.Locale

/**
 * App language, independent of the phone's: "" = follow the phone, "pl", "en". Android 13+ keeps the choice
 * itself (it is also in the system's per-app language settings); older versions get it from our preferences
 * through [wrap] in each activity.
 */
object Language {
    val TAGS = listOf("", "pl", "en")

    fun current(context: Context): String {
        val tag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.getSystemService(LocaleManager::class.java).applicationLocales.toLanguageTags()
        } else prefs(context).getString(KEY, "").orEmpty()
        return TAGS.firstOrNull { it.isNotEmpty() && tag.startsWith(it) }.orEmpty()
    }

    fun set(activity: Activity, tag: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // the system restarts the activities in the new language
            activity.getSystemService(LocaleManager::class.java).applicationLocales =
                if (tag.isEmpty()) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(tag)
        } else {
            prefs(activity).edit().putString(KEY, tag).apply()
            activity.recreate()
        }
    }

    /** Android 8–12: activity context in the chosen language (call from attachBaseContext). */
    fun wrap(base: Context): Context {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return base
        val tag = prefs(base).getString(KEY, "").orEmpty()
        if (tag.isEmpty()) return base
        val config = Configuration(base.resources.configuration).apply { setLocale(Locale.forLanguageTag(tag)) }
        return base.createConfigurationContext(config)
    }

    private fun prefs(context: Context) = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private const val KEY = "language"
}
