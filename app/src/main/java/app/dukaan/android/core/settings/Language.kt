package app.dukaan.android.core.settings

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/**
 * App language (M6): "" follows the phone (and Android 13+ per-app language), else "en" / "ur".
 * Applied by wrapping the Application and Activity contexts at start; a change restarts the
 * process so every screen and the app context pick it up (same pattern as import, D-53).
 */
fun Context.withAppLanguage(language: String): Context {
    if (language.isBlank()) return this
    val config = Configuration(resources.configuration)
    config.setLocale(Locale.forLanguageTag(language)) // also sets RTL layout direction for Urdu
    return createConfigurationContext(config)
}
