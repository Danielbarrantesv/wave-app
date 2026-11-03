package com.mp3tify.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.LruCache

object AlbumArtCache {
    private const val MAX_CACHE_BYTES = 8 * 1024 * 1024
    private val cache = object : LruCache<String, Bitmap>(MAX_CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    fun clear() { try { cache.evictAll() } catch (e: Exception) {} }

    fun decode(context: Context, path: String, sizePx: Int): Bitmap? {
        val key = "$path@$sizePx"
        cache.get(key)?.let { return it }
        val isContentUri = path.startsWith("content://")
        val looksLikeImageFile = !isContentUri && (
            path.endsWith(".jpg", ignoreCase = true) || path.endsWith(".jpeg", ignoreCase = true) ||
            path.endsWith(".png", ignoreCase = true) || path.endsWith(".webp", ignoreCase = true)
        )
        return try {
            if (looksLikeImageFile) {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(path, bounds)
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
                var s = 1
                if (bounds.outHeight > sizePx || bounds.outWidth > sizePx) {
                    val hH = bounds.outHeight / 2; val hW = bounds.outWidth / 2
                    while ((hH / s) >= sizePx && (hW / s) >= sizePx) s *= 2
                }
                val o = BitmapFactory.Options().apply { inSampleSize = s; inPreferredConfig = Bitmap.Config.RGB_565 }
                BitmapFactory.decodeFile(path, o)?.also { cache.put(key, it) }
            } else {
                val r = MediaMetadataRetriever()
                val bytes: ByteArray?
                try {
                    if (isContentUri) r.setDataSource(context, Uri.parse(path)) else r.setDataSource(path)
                    bytes = r.embeddedPicture
                } finally { try { r.release() } catch (_: Exception) {} }
                if (bytes == null || bytes.isEmpty()) null
                else {
                    val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, b)
                    var s = 1
                    if (b.outHeight > sizePx || b.outWidth > sizePx) {
                        val hH = b.outHeight / 2; val hW = b.outWidth / 2
                        while ((hH / s) >= sizePx && (hW / s) >= sizePx) s *= 2
                    }
                    val o = BitmapFactory.Options().apply { inSampleSize = s; inPreferredConfig = Bitmap.Config.RGB_565 }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)?.also { cache.put(key, it) }
                }
            }
        } catch (e: Exception) { null }
    }
}
