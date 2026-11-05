package com.mp3tify

import android.content.Context
import android.net.Uri
import com.google.gson.Gson
import com.mp3tify.data.repository.Repository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

object LastFmClient {
    private const val API_BASE = "https://ws.audioscrobbler.com/2.0/"
    private const val PREFS_NAME = "mp3tify_meta"
    private const val KEY_API_KEY = "lastfm_api_key"
    private const val DEFAULT_API_KEY = "8d326178e0b0636120d5fda1d01c675a"
    private val gson = Gson()

    fun getApiKey(context: Context): String? =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY_API_KEY, null) ?: DEFAULT_API_KEY
    fun setApiKey(context: Context, key: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putString(KEY_API_KEY, key).apply()
    }
    fun hasApiKey(context: Context): Boolean = !getApiKey(context).isNullOrBlank()

    data class TrackSearchResult(val title: String?, val artist: String?, val album: String?, val coverUrl: String?)

    suspend fun searchTrack(context: Context, title: String, artist: String?): TrackSearchResult? = withContext(Dispatchers.IO) {
        val apiKey = getApiKey(context) ?: return@withContext null
        if (title.isBlank()) return@withContext null
        try {
            val q = buildString {
                append(API_BASE).append("?method=track.search&track=").append(Uri.encode(title))
                if (!artist.isNullOrBlank()) append("&artist=").append(Uri.encode(artist))
                append("&api_key=").append(apiKey).append("&format=json&limit=1")
            }
            val json = fetchUrl(q) ?: return@withContext null
            val r = gson.fromJson(json, Map::class.java) ?: return@withContext null
            @Suppress("UNCHECKED_CAST")
            val tm = (r["results"] as? Map<String, Any?>)?.get("trackmatches") as? Map<String, Any?> ?: return@withContext null
            @Suppress("UNCHECKED_CAST")
            val tracks = tm["track"] as? List<Map<String, Any?>> ?: return@withContext null
            if (tracks.isEmpty()) return@withContext null
            val t = tracks[0]
            @Suppress("UNCHECKED_CAST")
            val imgs = t["image"] as? List<Map<String, Any?>>
            var cover: String? = null
            imgs?.reversed()?.forEach { val u = it["#text"] as? String; if (!u.isNullOrBlank()) { cover = u } }
            TrackSearchResult(t["name"] as? String, t["artist"] as? String, null, cover)
        } catch (e: Exception) { e.printStackTrace(); null }
    }

    suspend fun getTrackInfo(context: Context, title: String, artist: String): TrackSearchResult? = withContext(Dispatchers.IO) {
        val apiKey = getApiKey(context) ?: return@withContext null
        try {
            val q = buildString {
                append(API_BASE).append("?method=track.getInfo&track=").append(Uri.encode(title))
                append("&artist=").append(Uri.encode(artist)).append("&api_key=").append(apiKey).append("&format=json")
            }
            val json = fetchUrl(q) ?: return@withContext null
            val r = gson.fromJson(json, Map::class.java) ?: return@withContext null
            @Suppress("UNCHECKED_CAST")
            val t = r["track"] as? Map<String, Any?> ?: return@withContext null
            @Suppress("UNCHECKED_CAST")
            val al = t["album"] as? Map<String, Any?>
            val albumName = al?.get("#text") as? String
            @Suppress("UNCHECKED_CAST")
            val imgs = al?.get("image") as? List<Map<String, Any?>>
            var cover: String? = null
            imgs?.reversed()?.forEach { val u = it["#text"] as? String; if (!u.isNullOrBlank()) { cover = u } }
            @Suppress("UNCHECKED_CAST")
            val art = (t["artist"] as? Map<String, Any?>)?.get("#text") as? String
            TrackSearchResult(t["name"] as? String, art, albumName, cover)
        } catch (e: Exception) { e.printStackTrace(); null }
    }

    suspend fun downloadCover(context: Context, imageUrl: String, songFilePath: String): String? = withContext(Dispatchers.IO) {
        try {
            val bytes = fetchBytes(imageUrl) ?: return@withContext null
            val d = File(context.cacheDir, "art"); if (!d.exists()) d.mkdirs()
            val f = File(d, "art_lastfm_${pathHash(songFilePath)}.jpg")
            FileOutputStream(f).use { it.write(bytes) }; f.absolutePath
        } catch (e: Exception) { e.printStackTrace(); null }
    }

    suspend fun updateAllMetadata(context: Context, onProgress: (Int) -> Unit = {}): Pair<Int, Int> = withContext(Dispatchers.IO) {
        val repo = Repository.get(context); val songs = repo.allSongs(); val total = songs.size
        if (total == 0) return@withContext Pair(0, 0)
        var updated = 0
        for ((idx, song) in songs.withIndex()) {
            try {
                val title = song.title ?: song.fileName; val artist = song.artist ?: ""
                if (title.isBlank()) { onProgress(((idx + 1) * 100) / total); continue }
                val sr = searchTrack(context, title, artist)
                var cover = sr?.coverUrl; var album = sr?.album
                if (cover.isNullOrBlank() && artist.isNotBlank()) {
                    val info = getTrackInfo(context, title, artist)
                    if (info != null) { cover = info.coverUrl; if (album.isNullOrBlank()) album = info.album }
                }
                var newArt = song.artUri
                if (!cover.isNullOrBlank()) { downloadCover(context, cover, song.filePath)?.let { newArt = it } }
                var changed = false
                if (!album.isNullOrBlank() && song.album != album) changed = true
                if (newArt != song.artUri) changed = true
                if (changed) {
                    if (!album.isNullOrBlank()) repo.updateSongInfo(song.id, song.title, song.artist, album)
                    if (newArt != null && newArt != song.artUri) repo.updateSongPaths(song.id, song.filePath, newArt)
                    updated++
                }
            } catch (e: Exception) { e.printStackTrace() }
            onProgress(((idx + 1) * 100) / total)
        }
        try { com.mp3tify.ui.components.AlbumArtCache.clear() } catch (e: Exception) {}
        Pair(updated, total)
    }

    private fun fetchUrl(u: String): String? = try {
        val c = URL(u).openConnection() as HttpURLConnection; c.connectTimeout = 10000; c.readTimeout = 10000
        c.inputStream.bufferedReader().use { it.readText() }
    } catch (e: Exception) { null }

    private fun fetchBytes(u: String): ByteArray? = try {
        val c = URL(u).openConnection() as HttpURLConnection; c.connectTimeout = 15000; c.readTimeout = 15000
        c.inputStream.use { it.readBytes() }
    } catch (e: Exception) { null }

    private fun pathHash(k: String): String = try {
        MessageDigest.getInstance("SHA-1").digest(k.toByteArray()).joinToString("") { "%02x".format(it) }
    } catch (e: Exception) { k.hashCode().toUInt().toString() }
}
