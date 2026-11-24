package com.mp3tify.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.LruCache
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors

object AlbumArtCache {
    private const val MAX_CACHE_BYTES = 8 * 1024 * 1024
    val dispatcher = Executors.newFixedThreadPool(2).asCoroutineDispatcher()
    private val cache = object : LruCache<String, Bitmap>(MAX_CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    private val noArtKeys = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    fun clear() { try { cache.evictAll(); noArtKeys.clear() } catch (e: Exception) {} }
    fun peek(path: String, sizePx: Int): Bitmap? = cache.get("$path@$sizePx")

    /**
     * Decodes album art. If [path] is a JPG cache file that was deleted (cache
     * wiped on uninstall), falls back to [fallbackPath] (the audio content://
     * URI) to extract the embedded ID3 picture on the fly.
     */
    fun decode(context: Context, path: String, sizePx: Int, fallbackPath: String? = null): Bitmap? {
        val key = "$path@$sizePx"
        cache.get(key)?.let { return it }
        if (noArtKeys.contains(key)) return null
        val isContentUri = path.startsWith("content://")
        val looksLikeImageFile = !isContentUri && (
            path.endsWith(".jpg", ignoreCase = true) || path.endsWith(".jpeg", ignoreCase = true) ||
            path.endsWith(".png", ignoreCase = true) || path.endsWith(".webp", ignoreCase = true)
        )
        val res: Bitmap? = try {
            if (looksLikeImageFile) {
                val bmp = decodeImageFile(path, sizePx)
                if (bmp != null) { cache.put(key, bmp); bmp }
                else {
                    // JPG cache file missing — fall back to audio content:// URI.
                    val fb = fallbackPath?.takeIf { it.isNotBlank() && it != path }
                    if (fb != null) {
                        val recovered = decodeAudioEmbedded(context, fb, sizePx)
                        if (recovered != null) cache.put(key, recovered)
                        recovered
                    } else null
                }
            } else {
                decodeAudioEmbedded(context, path, sizePx)?.also { cache.put(key, it) }
            }
        } catch (e: Exception) { null }
        if (res == null) { if (noArtKeys.size > 4000) noArtKeys.clear(); noArtKeys.add(key) }
        return res
    }

    private fun decodeImageFile(path: String, sizePx: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var s = 1
        if (bounds.outHeight > sizePx || bounds.outWidth > sizePx) {
            val hH = bounds.outHeight / 2; val hW = bounds.outWidth / 2
            while ((hH / s) >= sizePx && (hW / s) >= sizePx) s *= 2
        }
        val o = BitmapFactory.Options().apply { inSampleSize = s; inPreferredConfig = Bitmap.Config.RGB_565 }
        return BitmapFactory.decodeFile(path, o)
    }

    private fun decodeAudioEmbedded(context: Context, path: String, sizePx: Int): Bitmap? {
        val isContentUri = path.startsWith("content://")
        val r = MediaMetadataRetriever()
        val bytes: ByteArray?
        try {
            if (isContentUri) r.setDataSource(context, Uri.parse(path)) else r.setDataSource(path)
            bytes = r.embeddedPicture
        } finally { try { r.release() } catch (_: Exception) {} }
        if (bytes == null || bytes.isEmpty()) return null
        val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, b)
        var s = 1
        if (b.outHeight > sizePx || b.outWidth > sizePx) {
            val hH = b.outHeight / 2; val hW = b.outWidth / 2
            while ((hH / s) >= sizePx && (hW / s) >= sizePx) s *= 2
        }
        val o = BitmapFactory.Options().apply { inSampleSize = s; inPreferredConfig = Bitmap.Config.RGB_565 }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
    }
}
