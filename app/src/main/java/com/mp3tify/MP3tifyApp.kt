package com.mp3tify

import android.app.Application
import android.content.Context
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.mp3tify.data.db.Mp3tifyDatabase
import com.mp3tify.data.repository.Repository
import com.mp3tify.util.Preferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MP3tifyApp : Application(), ImageLoaderFactory {

    override fun onCreate() {
        super.onCreate()
        // One-shot DB reset (only once, tracked in SharedPreferences "mp3tify_meta").
        try {
            oneShotDbReset()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        // Preferences must be initialized before anything reads its flows.
        try {
            Preferences.init(this)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        // Repository singleton (Room-backed).
        try {
            Repository.init(this)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        // Equalizer: store app context so it can generate a fresh audio session id.
        try {
            com.mp3tify.equalizer.EqualizerManager.init(this)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        // CRITICAL (v2.5.6 anti-hibernacion): migrar portadas de cacheDir (borrable
        // por "Pausar actividad en la app si no se usa" de Android) a filesDir
        // (persistente). One-shot con marca en mp3tify_meta.
        try {
            val meta = getSharedPreferences("mp3tify_meta", Context.MODE_PRIVATE)
            if (!meta.getBoolean("art_migrated_to_filesdir", false)) {
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        Repository.get(this@MP3tifyApp).migrateArtToFilesDir()
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                    meta.edit().putBoolean("art_migrated_to_filesdir", true).apply()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun oneShotDbReset() {
        val meta = getSharedPreferences("mp3tify_meta", Context.MODE_PRIVATE)
        if (meta.getBoolean("db_v4_reset_done", false)) return
        try {
            Mp3tifyDatabase.reset(this)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        meta.edit().putBoolean("db_v4_reset_done", true).apply()
    }

    /** Coil image loader: 25% of available memory for the bitmap pool. */
    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.25)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizeBytes(64L * 1024 * 1024)
                    .build()
            }
            .crossfade(true)
            .build()
    }
}
