package com.mp3tify.backup

import android.content.Context
import android.content.Intent
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

    /**
     * Verifica si las canciones restauradas tienen filePaths accesibles.
     * Tras una reinstalacion, los URIs content:// del backup caducan.
     */
    suspend fun needsFolderAccess(context: Context): Boolean = withContext(Dispatchers.IO) {
        try {
            val repo = Repository.get(context)
            val songs = repo.allSongs()
            if (songs.isEmpty()) return@withContext false
            songs.take(5).any { song ->
                try {
                    context.contentResolver.openInputStream(Uri.parse(song.filePath))?.use { true } ?: false
                } catch (e: Exception) { false } == false
            }
        } catch (e: Exception) { e.printStackTrace(); false }
    }

    /**
     * Re-mapea los filePaths de las canciones por nombre de archivo.
     */
    suspend fun remapFilePaths(
        context: Context,
        treeUriString: String,
        onProgress: (Int) -> Unit = {}
    ): Pair<Int, Int> = withContext(Dispatchers.IO) {
        var remapped = 0; var total = 0
        try {
            val repo = Repository.get(context)
            val songs = repo.allSongs()
            total = songs.size
            if (total == 0) return@withContext Pair(0, 0)
            try {
                context.contentResolver.takePersistableUriPermission(
                    Uri.parse(treeUriString),
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (e: Exception) { e.printStackTrace() }
            BackupUriManager.saveTreeUri(context, Uri.parse(treeUriString))
            val tree = DocumentFile.fromTreeUri(context, Uri.parse(treeUriString))
            if (tree == null) return@withContext Pair(0, total)
            val files = mutableListOf<DocumentFile>()
            fun walk(root: DocumentFile, out: MutableList<DocumentFile>, depth: Int) {
                if (depth > 8) return
                try { for (child in root.listFiles()) {
                    if (child.isDirectory) walk(child, out, depth + 1) else out.add(child)
                } } catch (e: Exception) {}
            }
            walk(tree, files, 0)
            val nameToUri = files.associate { (it.name ?: "") to it.uri.toString() }
            val artDir = java.io.File(context.cacheDir, "art")
            if (!artDir.exists()) artDir.mkdirs()
            songs.forEachIndexed { idx, song ->
                val isAccessible = try {
                    context.contentResolver.openInputStream(Uri.parse(song.filePath))?.use { true } ?: false
                } catch (e: Exception) { false }
                if (!isAccessible) {
                    val newUri = nameToUri[song.fileName]
                    if (newUri != null) {
                        try {
                            val retriever = android.media.MediaMetadataRetriever()
                            var newArtUri: String? = song.artUri
                            try {
                                retriever.setDataSource(context, Uri.parse(newUri))
                                val bytes = retriever.embeddedPicture
                                if (bytes != null && bytes.isNotEmpty()) {
                                    val hash = pathHash(newUri)
                                    val artFile = java.io.File(artDir, "art_$hash.jpg")
                                    if (!artFile.exists()) java.io.FileOutputStream(artFile).use { it.write(bytes) }
                                    newArtUri = artFile.absolutePath
                                }
                            } finally { try { retriever.release() } catch (_: Exception) {} }
                            repo.updateSongPaths(song.id, newUri, newArtUri)
                            remapped++
                        } catch (e: Exception) { e.printStackTrace() }
                    }
                }
                onProgress(((idx + 1) * 100) / total)
            }
        } catch (e: Exception) { e.printStackTrace() }
        Pair(remapped, total)
    }

    /** Restores songs + playlists, deduping against what already exists.
     *  [onProgress] recibe el porcentaje (0-100) para la barra de progreso. */
    suspend fun restore(
        context: Context,
        backup: BackupV2,
        onProgress: (Int) -> Unit = {}
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val repo = Repository.get(context)
            val existing = repo.allSongs()
            val existingPaths = existing.map { it.filePath }.toHashSet()
            val existingFps = existing.mapNotNull { it.fingerprint }.toHashSet()
            val idMap = mutableMapOf<Long, Long>()

            // Songs (70% del progreso)
            val totalSongs = backup.songs.size.coerceAtLeast(1)
            backup.songs.forEachIndexed { idx, s ->
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
                onProgress(((idx + 1) * 70) / totalSongs)
            }

            // Playlists (30% del progreso) — never overwrite; rename on collision
            val existingNames = repo.allPlaylists().map { it.name }.toHashSet()
            val totalPlaylists = backup.playlists.size.coerceAtLeast(1)
            backup.playlists.forEachIndexed { idx, pl ->
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
                onProgress(70 + (((idx + 1) * 30) / totalPlaylists))
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
            onProgress(100)
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    private fun pathHash(key: String): String {
        return try {
            val md = java.security.MessageDigest.getInstance("SHA-1")
            md.digest(key.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            key.hashCode().toUInt().toString()
        }
    }
}
