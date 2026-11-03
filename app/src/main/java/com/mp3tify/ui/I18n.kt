package com.mp3tify.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import com.mp3tify.util.Preferences
import com.mp3tify.util.Strings

/** Currently selected language code ("es", "en", "ja", "de", "fr", "ko"). */
val LocalAppLanguage = staticCompositionLocalOf { "es" }

/**
 * Global language holder — updated by [ProvideAppLanguage] on every
 * recomposition, so callbacks (launchers, lambdas) can read it without
 * being @Composable.
 */
object I18n {
    @Volatile
    var currentLang: String = "es"
        private set

    /** Non-composable translation — safe to call from callbacks. */
    fun t(key: String): String = Strings.t(key, currentLang)

    /** Non-composable translation with format args. */
    fun tf(key: String, vararg args: Any): String = Strings.f(key, currentLang, *args)

    internal fun setLang(code: String) { currentLang = code }
}

/** Provides the app language from DataStore to the whole composition. */
@Composable
fun ProvideAppLanguage(content: @Composable () -> Unit) {
    val lang by Preferences.languageFlow.collectAsState(initial = "es")
    I18n.setLang(lang)
    CompositionLocalProvider(LocalAppLanguage provides lang) {
        content()
    }
}

/** Translate [key] in the current app language (Composable). */
@Composable
fun t(key: String): String = I18n.t(key)

/** Translate with placeholders (Composable). */
@Composable
fun tf(key: String, vararg args: Any): String = I18n.tf(key, *args)
