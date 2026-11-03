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
     * CRITICAL FIX (Bug restore tras desinstalar): verifica si las canciones
     * restauradas tienen filePaths accesibles. Tras una reinstalación, los URIs
     * content:// del backup caducan (permissions perdidos) → las canciones no
     * suenan. Retorna true si ALGUNA canción no es accesible (necesita re-selección).
     */
    suspend fun needsFolderAccess(context: Context): Boolean = withContext(Dispatchers.IO) {
        try {
            val repo = Repository.get(context)
            val songs = repo.allSongs()
            if (songs.isEmpty()) return@withContext false
            // Verificar las primeras 5 canciones (rápido, no revisa todas).
            songs.take(5).any { song ->
                try {
                    context.contentResolver.openInputStream(android.net.Uri.parse(song.filePath))?.use { true } ?: false
                } catch (e: Exception) { false } == false
            }
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /**
     * CRITICAL FIX (Bug restore tras desinstalar — canciones no suenan):
     * Tras desinstalar la app, los persistable URI permissions se pierden y
     * los filePath content:// del backup ya no son accesibles. El usuario debe
     * volver a seleccionar la carpeta de música. Este método toma el tree URI
     * nuevo, recorre las canciones de la DB, y si una canción tiene un filePath
     * inaccesible, busca un archivo con el mismo fileName en el nuevo tree y
     * actualiza el filePath (y re-extrae la portada).
     *
     * Retorna un par (remapped, total) donde remapped = cuántas canciones se
     * pudieron re-mapear y total = cuántas se intentaron.
     */
    suspend fun remapFilePaths(
        context: Context,
        treeUriString: String,
        onProgress: (Int) -> Unit = {}
    ): Pair<Int, Int> = withContext(Dispatchers.IO) {
        var remapped = 0
        var total = 0
        try {
            val repo = Repository.get(context)
            val songs = repo.allSongs()
            total = songs.size
            if (total == 0) return@withContext Pair(0, 0)

            // Tomar el persistable permission del tree URI.
            try {
                context.contentResolver.takePersistableUriPermission(
                    android.net.Uri.parse(treeUriString),
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
                )
            } catch (e: Exception) {
                e.printStackTrace()
            }
            // Guardar el tree URI en BackupUriManager para futuros restores.
            com.mp3tify.backup.BackupUriManager.saveTreeUri(context, android.net.Uri.parse(treeUriString))

            // Recorrer el tree y construir un mapa fileName → uri (para búsqueda rápida).
            val tree = DocumentFile.fromTreeUri(context, android.net.Uri.parse(treeUriString))
            if (tree == null) return@withContext Pair(0, total)
            val files = mutableListOf<DocumentFile>()
            walkTreeForRemap(tree, files, 0)
            val nameToUri = files.associate { (it.name ?: "") to it.uri.toString() }

            val artDir = java.io.File(context.cacheDir, "art")
            if (!artDir.exists()) artDir.mkdirs()

            songs.forEachIndexed { idx, song ->
                // Verificar si el filePath actual es accesible.
                val filePath = song.filePath
                val isAccessible = try {
                    context.contentResolver.openInputStream(android.net.Uri.parse(filePath))?.use { true } ?: false
                } catch (e: Exception) { false }
                if (!isAccessible) {
                    // Buscar el archivo por fileName en el tree nuevo.
                    val fileName = song.fileName
                    val newUri = nameToUri[fileName]
                    if (newUri != null) {
                        // Actualizar el filePath de la canción en la DB.
                        try {
                            // Re-extraer la portada del nuevo filePath.
                            val retriever = android.media.MediaMetadataRetriever()
                            var newArtUri: String? = song.artUri
                            try {
                                retriever.setDataSource(context, android.net.Uri.parse(newUri))
                                val bytes = retriever.embeddedPicture
                                if (bytes != null && bytes.isNotEmpty()) {
                                    val hash = pathHash(newUri)
                                    val artFile = java.io.File(artDir, "art_$hash.jpg")
                                    if (!artFile.exists()) {
                                        java.io.FileOutputStream(artFile).use { it.write(bytes) }
                                    }
                                    newArtUri = artFile.absolutePath
                                }
                            } finally { try { retriever.release() } catch (_: Exception) {} }
                            // Actualizar la canción en la DB con el nuevo filePath y artUri.
                            repo.updateSongPaths(song.id, newUri, newArtUri)
                            remapped++
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                }
                onProgress(((idx + 1) * 100) / total)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        Pair(remapped, total)
    }

    private fun walkTreeForRemap(root: DocumentFile, out: MutableList<DocumentFile>, depth: Int) {
        if (depth > 8) return
        try {
            for (child in root.listFiles()) {
                if (child.isDirectory) {
                    walkTreeForRemap(child, out, depth + 1)
                } else {
                    out.add(child)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Restores songs + playlists, deduping against what already exists.
     *
     * CRITICAL FIX (Bug restore a medias): ahora acepta un callback [onProgress]
     * que recibe el porcentaje (0-100) para que la UI muestre una barra de
     * progreso y bloquee la salida hasta que termine.
     */
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

            // CRITICAL FIX (Bug portadas restauradas): al restaurar canciones del
            // backup, el artUri del JSON apunta a un JPG en cacheDir/art/ que
            // fue borrado al desinstalar. Aquí re-extraemos la portada embebida
            // del archivo de audio (vía filePath, accesible gracias a
            // BackupUriManager.restoreTreeUris) y regeneramos el artUri con un
            // path válido al cache recién creado.
            val artDir = java.io.File(context.cacheDir, "art")
            if (!artDir.exists()) artDir.mkdirs()

            // Songs (70% del progreso)
            val totalSongs = backup.songs.size.coerceAtLeast(1)
            backup.songs.forEachIndexed { idx, s ->
                val known = existingPaths.contains(s.filePath) ||
                    (s.fingerprint != null && existingFps.contains(s.fingerprint))
                if (!known) {
                    // Re-extraer la portada del archivo de audio si está accesible.
                    val songWithArt = reExtractArt(context, s, artDir)
                    val newId = repo.insertSong(songWithArt.copy(id = 0))
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
                // Reportar progreso: 0-70% según avance de canciones.
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
                // Reportar progreso: 70-100% según avance de playlists.
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
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /**
     * CRITICAL FIX (Bug portadas restauradas): re-extrae la portada embebida
     * del archivo de audio (vía filePath) y la guarda en cacheDir/art/ con un
     * hash del filePath. Retorna una copia de la Song con el artUri actualizado
     * apuntando al nuevo JPG cacheado. Si el archivo de audio no es accesible
     * (URI caducó o no hay permission), retorna la Song original sin cambios.
     */
    private suspend fun reExtractArt(
        context: Context,
        song: Song,
        artDir: java.io.File
    ): Song {
        return try {
            val filePath = song.filePath
            if (filePath.isBlank()) return song
            // Verificar si el artUri original aún apunta a un archivo existente.
            val origArt = song.artUri
            if (origArt != null && !origArt.startsWith("content://") && java.io.File(origArt).exists()) {
                return song  // El cache de portada aún existe, no hay nada que re-extraer.
            }
            // Re-extraer la portada embebida del archivo de audio.
            val retriever = android.media.MediaMetadataRetriever()
            val bytes: ByteArray?
            try {
                if (filePath.startsWith("content://")) {
                    retriever.setDataSource(context, android.net.Uri.parse(filePath))
                } else {
                    retriever.setDataSource(filePath)
                }
                bytes = retriever.embeddedPicture
            } finally {
                try { retriever.release() } catch (_: Exception) {}
            }
            if (bytes == null || bytes.isEmpty()) return song
            // Guardar el JPG en cache con un hash del filePath (igual que buildSong).
            val hash = pathHash(filePath)
            val artFile = java.io.File(artDir, "art_$hash.jpg")
            if (!artFile.exists()) {
                java.io.FileOutputStream(artFile).use { it.write(bytes) }
            }
            song.copy(artUri = artFile.absolutePath)
        } catch (e: Exception) {
            e.printStackTrace()
            song  // En caso de error, devolver la song original.
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
