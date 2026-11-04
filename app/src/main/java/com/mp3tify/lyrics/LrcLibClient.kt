package com.mp3tify.lyrics

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest

/**
 * lrclib.net lyrics client.
 *
 * CACHE-FIRST: cacheDir/lyrics/<sha1("artist|title" lowercase)>.lrc is checked before
 * any network call; results from the network are written back to the cache.
 */
object LrcLibClient {

    private const val BASE = "https://lrclib.net/api"
    private const val USER_AGENT = "MP3tify/2.4"
    private const val TIMEOUT_MS = 10_000

    private fun cacheFile(context: Context, artist: String, track: String): File {
        val key = "$artist|$track".lowercase()
        val digest = MessageDigest.getInstance("SHA-1").digest(key.toByteArray(Charsets.UTF_8))
        val hex = digest.joinToString("") { "%02x".format(it) }
        val dir = File(context.cacheDir, "lyrics")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, "$hex.lrc")
    }

    fun isCached(context: Context, artist: String, track: String): Boolean {
        return try {
            cacheFile(context, artist, track).exists()
        } catch (e: Exception) {
            false
        }
    }

    /** CACHE-FIRST lookup: returns synced or plain lyrics, or null. */
    fun getLyrics(
        context: Context,
        artist: String,
        track: String,
        album: String? = null,
        durationSec: Long? = null
    ): String? {
        val cache = try {
            cacheFile(context, artist, track)
        } catch (e: Exception) {
            null
        }
        if (cache != null && cache.exists()) {
            val cachedText = try {
                cache.readText(Charsets.UTF_8)
            } catch (e: Exception) {
                null
            }
            if (!cachedText.isNullOrBlank()) return cachedText
        }

        val fetched = fetchFromNetwork(artist, track, album, durationSec)
        if (!fetched.isNullOrBlank() && cache != null) {
            try {
                cache.writeText(fetched, Charsets.UTF_8)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        return fetched
    }

    private fun fetchFromNetwork(
        artist: String,
        track: String,
        album: String?,
        durationSec: Long?
    ): String? {
        return try {
            // 1) Exact /api/get
            val sb = StringBuilder("$BASE/get?artist_name=${enc(artist)}&track_name=${enc(track)}")
            if (!album.isNullOrBlank()) sb.append("&album_name=${enc(album)}")
            if (durationSec != null && durationSec > 0) sb.append("&duration=$durationSec")
            val exact = httpGet(sb.toString())
            if (!exact.isNullOrBlank()) {
                val extracted = extractLyrics(exact)
                if (!extracted.isNullOrBlank()) return extracted
            }

            // 2) /api/search fallback
            val searchUrl = "$BASE/search?track_name=${enc(track)}&artist_name=${enc(artist)}"
            val search = httpGet(searchUrl)
            if (!search.isNullOrBlank()) {
                return extractLyrics(search)
            }
            null
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /** /api/get returns a JSON object; /api/search returns an array. Prefer synced lyrics. */
    private fun extractLyrics(body: String): String? {
        return try {
            val trimmed = body.trim()
            if (trimmed.startsWith("[")) {
                val arr = JSONArray(trimmed)
                for (i in 0 until arr.length()) {
                    val synced = arr.getJSONObject(i).optString("syncedLyrics", "")
                    if (synced.isNotBlank()) return synced
                }
                for (i in 0 until arr.length()) {
                    val plain = arr.getJSONObject(i).optString("plainLyrics", "")
                    if (plain.isNotBlank()) return plain
                }
                null
            } else {
                val obj = JSONObject(trimmed)
                val synced = obj.optString("syncedLyrics", "")
                if (synced.isNotBlank()) {
                    synced
                } else {
                    obj.optString("plainLyrics", "").takeIf { it.isNotBlank() }
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun httpGet(urlStr: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                setRequestProperty("User-Agent", USER_AGENT)
            }
            val code = conn.responseCode
            if (code in 200..299) {
                conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            } else {
                null
            }
        } catch (e: Exception) {
            null
        } finally {
            try {
                conn?.disconnect()
            } catch (e: Exception) {
            }
        }
    }

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")
}
