package com.mp3tify.backup

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.google.gson.Gson
import com.mp3tify.data.model.Playlist
import com.mp3tify.data.model.PlaylistSong
import com.mp3tify.data.model.Song
import com.mp3tify.data.repository.Repository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Backup envelope (v2): songs, playlists and playlist membership. */
data class BackupV2(
    val version: Int = 2,
    val createdAt: Long = 0,
    val songs: List<Song> = emptyList(),
    val playlists: List<Playlist> = emptyList(),
    val playlistSongs: List<PlaylistSong> = emptyList()
)

object BackupManager {

    private val gson = Gson()

    /** Snapshot of the whole library + playlists. */
    suspend fun buildBackup(context: Context): BackupV2? = withContext(Dispatchers.IO) {
        try {
            val repo = Repository.get(context)
            val songs = repo.allSongs()
            val playlists = repo.allPlaylists()
            val entries = mutableListOf<PlaylistSong>()
            playlists.forEach { pl -> entries.addAll(repo.playlistEntries(pl.id)) }
            BackupV2(
                version = 2,
                createdAt = System.currentTimeMillis(),
                songs = songs,
                playlists = playlists,
                playlistSongs = entries
            )
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /** Writes a JSON backup to an arbitrary SAF file uri. */
    suspend fun writeBackupToUri(context: Context, uri: Uri): Boolean = withContext(Dispatchers.IO) {
        try {
            val backup = buildBackup(context) ?: return@withContext false
            context.contentResolver.openOutputStream(uri)?.use { os ->
                os.write(gson.toJson(backup).toByteArray(Charsets.UTF_8))
                os.flush()
            } ?: return@withContext false
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /** Writes a timestamped JSON backup into a SAF tree folder. */
    suspend fun writeToFolder(context: Context, folderUriString: String): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val tree = DocumentFile.fromTreeUri(context, Uri.parse(folderUriString))
                    ?: return@withContext false
                val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                val file = tree.createFile("application/json", "mp3tify_backup_$stamp.json")
                    ?: return@withContext false
                writeBackupToUri(context, file.uri)
            } catch (e: Exception) {
                e.printStackTrace()
                false
            }
        }

    suspend fun readFromFile(context: Context, fileUriString: String): BackupV2? =
        withContext(Dispatchers.IO) {
            try {
                val json = context.contentResolver.openInputStream(Uri.parse(fileUriString))?.use {
                    it.readBytes().toString(Charsets.UTF_8)
                } ?: return@withContext null
                gson.fromJson(json, BackupV2::class.java)
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }

    /** Restores songs + playlists, deduping against what already exists. */
    suspend fun restore(context: Context, backup: BackupV2): Boolean = withContext(Dispatchers.IO) {
        try {
            val repo = Repository.get(context)
            val existing = repo.allSongs()
            val existingPaths = existing.map { it.filePath }.toHashSet()
            val existingFps = existing.mapNotNull { it.fingerprint }.toHashSet()
            val idMap = mutableMapOf<Long, Long>()

            // Songs
            backup.songs.forEach { s ->
                val known = existingPaths.contains(s.filePath) ||
                    (s.fingerprint != null && existingFps.contains(s.fingerprint))
                if (!known) {
                    val newId = repo.insertSong(s.copy(id = 0))
                    if (newId > 0) {
                        idMap[s.id] = newId
                        existingPaths.add(s.filePath)
                        if (s.fingerprint != null) existingFps.add(s.fingerprint!!)
                    }
                } else {
                    val match = existing.find { it.filePath == s.filePath }
                        ?: existing.find { it.fingerprint != null && it.fingerprint == s.fingerprint }
                    if (match != null) idMap[s.id] = match.id
                }
            }

            // Playlists (never overwrite; rename on collision)
            val existingNames = repo.allPlaylists().map { it.name }.toHashSet()
            backup.playlists.forEach { pl ->
                val name = if (existingNames.contains(pl.name)) "${pl.name} (restaurada)" else pl.name
                existingNames.add(name)
                val newPlId = repo.createPlaylist(name, pl.description, pl.folderUri)
                if (newPlId > 0) {
                    val songIds = backup.playlistSongs
                        .filter { it.playlistId == pl.id }
                        .sortedBy { it.position }
                        .mapNotNull { idMap[it.songId] }
                    if (songIds.isNotEmpty()) repo.addSongsToPlaylist(newPlId, songIds)
                }
            }

            // Sync favorites into the protected "Favoritos" playlist if present.
            try {
                val favPl = repo.allPlaylists().firstOrNull { it.name == Repository.FAVORITES_NAME }
                if (favPl != null) {
                    val favIds = backup.songs.filter { it.isFavorite }.mapNotNull { idMap[it.id] }
                    if (favIds.isNotEmpty()) repo.addSongsToPlaylist(favPl.id, favIds)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
}
