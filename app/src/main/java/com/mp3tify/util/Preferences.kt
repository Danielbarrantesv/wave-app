package com.mp3tify.util

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.mp3tifyDataStore: DataStore<Preferences> by preferencesDataStore(name = "mp3tify_prefs")

object Preferences {

    private var appCtx: Context? = null

    // ------------------------------------------------------------------
    // Keys
    // ------------------------------------------------------------------
    // CRITICAL — Do NOT remove: keepScreenOn and keepAppOpen MUST default to true.
    const val KEY_KEEP_SCREEN_ON = "keep_screen_on"
    const val KEY_KEEP_APP_OPEN = "keep_app_open"
    private const val KEY_SHUFFLE = "shuffle"
    private const val KEY_REPEAT_MODE = "repeat_mode"
    private const val KEY_VOLUME = "volume"
    private const val KEY_DARK_THEME = "dark_theme"
    private const val KEY_ACCENT_COLOR = "accent_color"
    private const val KEY_LANGUAGE = "language"

    // CRITICAL — Do NOT remove: keepScreenOn=true and keepAppOpen=true by default.
    private const val DEFAULT_KEEP_SCREEN_ON = true
    private const val DEFAULT_KEEP_APP_OPEN = true
    private const val DEFAULT_SHUFFLE = false
    private const val DEFAULT_REPEAT_MODE = 0
    private const val DEFAULT_VOLUME = 1.0f
    private const val DEFAULT_DARK_THEME = true
    private const val DEFAULT_ACCENT_COLOR = "verde"
    private const val DEFAULT_LANGUAGE = "es"

    fun init(context: Context) {
        if (appCtx == null) {
            appCtx = context.applicationContext
        }
    }

    private fun ctx(): Context {
        val c = appCtx
        if (c == null) {
            throw IllegalStateException("Preferences not initialized. Call Preferences.init(context) first.")
        }
        return c
    }

    // ------------------------------------------------------------------
    // Flows (lazy so they resolve after init(context))
    // ------------------------------------------------------------------

    // CRITICAL — Do NOT remove: keepScreenOn flow, default true.
    val keepScreenOnFlow: Flow<Boolean> by lazy {
        ctx().mp3tifyDataStore.data.map { it[booleanPreferencesKey(KEY_KEEP_SCREEN_ON)] ?: DEFAULT_KEEP_SCREEN_ON }
    }

    // CRITICAL — Do NOT remove: keepAppOpen flow, default true.
    val keepAppOpenFlow: Flow<Boolean> by lazy {
        ctx().mp3tifyDataStore.data.map { it[booleanPreferencesKey(KEY_KEEP_APP_OPEN)] ?: DEFAULT_KEEP_APP_OPEN }
    }

    val shuffleFlow: Flow<Boolean> by lazy {
        ctx().mp3tifyDataStore.data.map { it[booleanPreferencesKey(KEY_SHUFFLE)] ?: DEFAULT_SHUFFLE }
    }

    val repeatModeFlow: Flow<Int> by lazy {
        ctx().mp3tifyDataStore.data.map { it[intPreferencesKey(KEY_REPEAT_MODE)] ?: DEFAULT_REPEAT_MODE }
    }

    val volumeFlow: Flow<Float> by lazy {
        ctx().mp3tifyDataStore.data.map { it[floatPreferencesKey(KEY_VOLUME)] ?: DEFAULT_VOLUME }
    }

    val darkThemeFlow: Flow<Boolean> by lazy {
        ctx().mp3tifyDataStore.data.map { it[booleanPreferencesKey(KEY_DARK_THEME)] ?: DEFAULT_DARK_THEME }
    }

    val accentColorFlow: Flow<String> by lazy {
        ctx().mp3tifyDataStore.data.map { it[stringPreferencesKey(KEY_ACCENT_COLOR)] ?: DEFAULT_ACCENT_COLOR }
    }

    val languageFlow: Flow<String> by lazy {
        ctx().mp3tifyDataStore.data.map { it[stringPreferencesKey(KEY_LANGUAGE)] ?: DEFAULT_LANGUAGE }
    }

    // ------------------------------------------------------------------
    // One-shot reads
    // ------------------------------------------------------------------
    suspend fun keepScreenOnFirst(): Boolean =
        try {
            keepScreenOnFlow.first()
        } catch (e: Exception) {
            DEFAULT_KEEP_SCREEN_ON
        }

    suspend fun keepAppOpenFirst(): Boolean =
        try {
            keepAppOpenFlow.first()
        } catch (e: Exception) {
            DEFAULT_KEEP_APP_OPEN
        }

    // ------------------------------------------------------------------
    // Writers
    // ------------------------------------------------------------------
    suspend fun setKeepScreenOn(value: Boolean) {
        try {
            ctx().mp3tifyDataStore.edit { it[booleanPreferencesKey(KEY_KEEP_SCREEN_ON)] = value }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    suspend fun setKeepAppOpen(value: Boolean) {
        try {
            ctx().mp3tifyDataStore.edit { it[booleanPreferencesKey(KEY_KEEP_APP_OPEN)] = value }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    suspend fun setShuffle(value: Boolean) {
        try {
            ctx().mp3tifyDataStore.edit { it[booleanPreferencesKey(KEY_SHUFFLE)] = value }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    suspend fun setRepeatMode(value: Int) {
        try {
            ctx().mp3tifyDataStore.edit { it[intPreferencesKey(KEY_REPEAT_MODE)] = value }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    suspend fun setVolume(value: Float) {
        try {
            ctx().mp3tifyDataStore.edit { it[floatPreferencesKey(KEY_VOLUME)] = value }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    suspend fun setDarkTheme(value: Boolean) {
        try {
            ctx().mp3tifyDataStore.edit { it[booleanPreferencesKey(KEY_DARK_THEME)] = value }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    suspend fun setAccentColor(value: String) {
        try {
            ctx().mp3tifyDataStore.edit { it[stringPreferencesKey(KEY_ACCENT_COLOR)] = value }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    suspend fun setLanguage(value: String) {
        try {
            ctx().mp3tifyDataStore.edit { it[stringPreferencesKey(KEY_LANGUAGE)] = value }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
