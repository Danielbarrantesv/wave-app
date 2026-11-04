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
