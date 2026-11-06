package com.mp3tify.data.repository

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.mp3tify.data.db.Mp3tifyDatabase
import com.mp3tify.data.model.Playlist
import com.mp3tify.data.model.PlaylistSong
import com.mp3tify.data.model.Song
import com.mp3tify.data.model.displayArtist
import com.mp3tify.util.Fingerprinter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

class Repository private constructor(private val context: Context) {

    companion object {
        /** The default playlist is protected: it can never be renamed or deleted. */
        const val FAVORITES_NAME = "Favoritos"

        @Volatile
        private var instance: Repository? = null

        fun init(context: Context) {
            if (instance == null) {
                synchronized(this) {
                    if (instance == null) {
                        instance = Repository(context.applicationContext)
                    }
                }
            }
        }

        fun get(): Repository {
            val i = instance
            if (i == null) {
                throw IllegalStateException("Repository not initialized. Call Repository.init(context) first.")
            }
            return i
        }

        fun get(context: Context): Repository {
            init(context)
            return instance!!
        }
    }

    private val db: Mp3tifyDatabase by lazy { Mp3tifyDatabase.get(context) }
    private val songDao by lazy { db.songDao() }
    private val playlistDao by lazy { db.playlistDao() }

    enum class SortOrder { RECENTLY_ADDED, RECENTLY_PLAYED, TITLE, ARTIST, DURATION }

    data class ImportResult(val added: Int, val duplicates: Int, val total: Int)

    // ------------------------------------------------------------------
    // Observers
    // ------------------------------------------------------------------
    fun observeSongs(): Flow<List<Song>> = songDao.observeAll()

    fun searchSongs(query: String): Flow<List<Song>> = songDao.search(query)

    fun observeSongsSorted(order: SortOrder): Flow<List<Song>> = when (order) {
        SortOrder.RECENTLY_ADDED -> songDao.observeByRecentlyAdded()
        SortOrder.RECENTLY_PLAYED -> songDao.observeByRecentlyPlayed()
        SortOrder.TITLE -> songDao.observeByTitle()
        SortOrder.ARTIST -> songDao.observeByArtist()
        SortOrder.DURATION -> songDao.observeByDuration()
    }

    fun observeFavorites(): Flow<List<Song>> = songDao.observeFavorites()

    /** Canciones reproducidas al menos una vez, ordenadas por última reproducción DESC, limitado a N. */
    fun observeRecentlyPlayed(limit: Int = 20): Flow<List<Song>> = songDao.observeRecentlyPlayed(limit)

    /**
     * Observa artistas agrupados a partir de todas las canciones (#4).
     * Devuelve un Flow de listas ordenadas por nombre de artista (case-insensitive).
     * Cada entrada es ArtistWithSongs(name, songs).
     * El UI filtra los que tienen >1 canción para mostrar en el slider de Artistas.
     */
    fun observeArtistsWithSongs(): Flow<List<ArtistWithSongs>> = songDao.observeAll().map { allSongs ->
        allSongs
            .groupBy { it.displayArtist }
            .map { (artist, songs) -> ArtistWithSongs(name = artist, songs = songs) }
            .sortedBy { it.name.lowercase() }
    }

    /** Data class para el slider de Artistas. */
    data class ArtistWithSongs(
        val name: String,
        val songs: List<Song>
    )

    fun playlistsFlow(): Flow<List<Playlist>> = playlistDao.observeAll()

    fun observePlaylist(playlistId: Long): Flow<Playlist?> = playlistDao.observeById(playlistId)

    fun observePlaylistSongs(playlistId: Long): Flow<List<Song>> =
        playlistDao.observeSongsInPlaylist(playlistId)

    // ------------------------------------------------------------------
    // One-shot reads (used by backup)
    // ------------------------------------------------------------------
    suspend fun allSongs(): List<Song> =
        try { songDao.listAll() } catch (e: Exception) { emptyList() }

    suspend fun allPlaylists(): List<Playlist> =
        try { playlistDao.getAll() } catch (e: Exception) { emptyList() }

    suspend fun playlistEntries(playlistId: Long): List<PlaylistSong> =
        try { playlistDao.getEntries(playlistId) } catch (e: Exception) { emptyList() }

    suspend fun insertSong(song: Song): Long =
        try { songDao.insert(song) } catch (e: Exception) { -1L }

    // ------------------------------------------------------------------
    // Mutations
    // ------------------------------------------------------------------

    /**
     * CRITICAL: try/catch ONLY — this must NEVER wipe the database.
     * Also syncs the toggle to the special "Favoritos" playlist.
     */
    suspend fun toggleFavorite(song: Song) {
        try {
            val newState = !song.isFavorite
            songDao.setFavorite(song.id, newState)
            val fav = playlistDao.getByName(FAVORITES_NAME)
            if (fav != null) {
                if (newState) {
                    playlistDao.addSongsToPlaylist(fav.id, listOf(song.id))
                } else {
                    playlistDao.removeSong(fav.id, song.id)
                }
            }
        } catch (e: Exception) {
            // CRITICAL: never wipe the DB here. Log and continue.
            e.printStackTrace()
        }
    }

    suspend fun recordPlay(songId: Long) {
        try {
            songDao.setLastPlayedAt(songId, System.currentTimeMillis())
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    suspend fun deleteSongById(songId: Long) {
        try {
            songDao.deleteById(songId)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    suspend fun updateSongInfo(songId: Long, title: String?, artist: String?, album: String?) {
        try {
            songDao.updateInfo(songId, title, artist, album)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    suspend fun updateSongPaths(songId: Long, filePath: String, artUri: String?) {
        try {
            songDao.updateSongPaths(songId, filePath, artUri)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    suspend fun createPlaylist(name: String, description: String? = null, folderUri: String? = null): Long {
        return try {
            val now = System.currentTimeMillis()
            playlistDao.insert(
                Playlist(
                    name = name,
                    description = description,
                    createdAt = now,
                    updatedAt = now,
                    folderUri = folderUri
                )
            )
        } catch (e: Exception) {
            e.printStackTrace()
            -1L
        }
    }

    suspend fun renamePlaylist(playlistId: Long, newName: String) {
        try {
            val pl = playlistDao.getById(playlistId) ?: return
            if (pl.name == FAVORITES_NAME) return // protected
            playlistDao.update(pl.copy(name = newName, updatedAt = System.currentTimeMillis()))
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    suspend fun deletePlaylist(playlistId: Long) {
        try {
            val pl = playlistDao.getById(playlistId) ?: return
            if (pl.name == FAVORITES_NAME) return // protected
            playlistDao.deleteById(playlistId)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    suspend fun addSongsToPlaylist(playlistId: Long, songIds: List<Long>) {
        try {
            playlistDao.addSongsToPlaylist(playlistId, songIds)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    suspend fun removeSongFromPlaylist(playlistId: Long, songId: Long) {
        try {
            playlistDao.removeSong(playlistId, songId)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    suspend fun setPlaylistFolder(playlistId: Long, folderUri: String) {
        try {
            val pl = playlistDao.getById(playlistId) ?: return
            playlistDao.update(pl.copy(folderUri = folderUri, updatedAt = System.currentTimeMillis()))
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    suspend fun ensureDefaultPlaylists() {
        try {
            if (playlistDao.getByName(FAVORITES_NAME) == null) {
                val now = System.currentTimeMillis()
                playlistDao.insert(
                    Playlist(
                        name = FAVORITES_NAME,
                        description = "default",
                        createdAt = now,
                        updatedAt = now
                    )
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    suspend fun wipeAllUserData() {
        try {
            songDao.deleteAll()
            playlistDao.deleteAllEntries()
            playlistDao.deleteAllExcept(FAVORITES_NAME)
            ensureDefaultPlaylists()
            try { File(context.cacheDir, "art").listFiles()?.forEach { it.delete() } } catch (e: Exception) {}
            try { File(context.cacheDir, "lyrics").listFiles()?.forEach { it.delete() } } catch (e: Exception) {}
            try { com.mp3tify.ui.components.AlbumArtCache.clear() } catch (e: Exception) {}
        } catch (e: Exception) { e.printStackTrace() }
    }

    suspend fun hasUserData(): Boolean {
        return try {
            songDao.count() > 0 || playlistDao.getAll().any { it.name != FAVORITES_NAME }
        } catch (e: Exception) { false }
    }

    // ------------------------------------------------------------------
    // Folder import (SAF tree walk + 4-level dedup)
    // ------------------------------------------------------------------

    suspend fun importFromFolder(folderUriString: String): ImportResult =
        importFolderInternal(folderUriString, null)

    suspend fun importFolderIntoPlaylist(folderUriString: String, playlistId: Long): ImportResult =
        importFolderInternal(folderUriString, playlistId)

    /**
     * Escanea una carpeta raiz y devuelve las subcarpetas que contienen al menos
     * un archivo de audio. NO recorre recursivamente (solo hijos directos).
     *
     * Usado por el dialog "Crear playlist por subcarpeta": cuando el usuario
     * selecciona una carpeta con subcarpetas, preguntamos si quiere crear una
     * playlist por cada subcarpeta con audio.
     *
     * @return lista de (subfolderName, subfolderUriString) para subcarpetas con audio
     */
    suspend fun findSubfoldersWithAudio(folderUriString: String): List<Pair<String, String>> =
        withContext(Dispatchers.IO) {
            val result = mutableListOf<Pair<String, String>>()
            try {
                val tree = DocumentFile.fromTreeUri(context, Uri.parse(folderUriString)) ?: return@withContext emptyList()
                for (child in tree.listFiles()) {
                    if (child.isDirectory) {
                        // Verificar si esta subcarpeta tiene al menos un archivo de audio (directo o recursivo)
                        val hasAudio = mutableListOf<DocumentFile>()
                        walkTree(child, hasAudio, 0)
                        if (hasAudio.any { isAudioFile(it) }) {
                            val name = child.name ?: "Subcarpeta"
                            result.add(name to child.uri.toString())
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
            result
        }

    /**
     * Importa una carpeta raiz creando una playlist por cada subcarpeta que tenga audio.
     * La carpeta raiz tambien se importa a la biblioteca general.
     *
     * Cada playlist se llama como la subcarpeta (capitalizada) y contiene las canciones
     * de esa subcarpeta. Si ya existe una playlist con ese nombre, se le agrega "(2)" etc.
     *
     * @param folderUriString URI de la carpeta raiz seleccionada
     * @param subfolders lista de (name, uri) devuelta por findSubfoldersWithAudio
     * @return total de canciones agregadas a la biblioteca
     */
    suspend fun importFolderWithSubPlaylists(
        folderUriString: String,
        subfolders: List<Pair<String, String>>
    ): Int = withContext(Dispatchers.IO) {
        var totalAdded = 0
        try {
            // 1. Importar toda la carpeta a la biblioteca general (incluye subcarpetas)
            val rootResult = importFolderInternal(folderUriString, null)
            totalAdded += rootResult.added

            // 2. Crear una playlist por cada subcarpeta con audio
            for ((rawName, subUri) in subfolders) {
                try {
                    // Capitalizar primera letra
                    val capitalizedName = if (rawName.isNotEmpty()) {
                        rawName.substring(0, 1).uppercase() + rawName.substring(1)
                    } else rawName
                    // Crear playlist (dedup de nombre: si existe, append (2), (3), etc.)
                    val uniqueName = ensureUniquePlaylistName(capitalizedName)
                    val playlistId = createPlaylist(uniqueName, description = "from-folder")
                    // Importar solo esta subcarpeta a la playlist recien creada
                    importFolderInternal(subUri, playlistId)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        totalAdded
    }

    /** Devuelve un nombre de playlist unico. Si "Rock" existe, devuelve "Rock (2)", etc. */
    private suspend fun ensureUniquePlaylistName(baseName: String): String {
        var name = baseName
        var suffix = 2
        while (playlistDao.getByName(name) != null) {
            name = "$baseName ($suffix)"
            suffix++
        }
        return name
    }

    /**
     * Importa URIs individuales (GetMultipleContent) a la library y opcionalmente a un playlist.
     */
    suspend fun importFiles(uris: List<String>, targetPlaylistId: Long? = null): ImportResult =
        withContext(Dispatchers.IO) {
            var added = 0; var duplicates = 0; var total = uris.size
            val playlistAttachIds = mutableListOf<Long>()
            try {
                val existing = songDao.listAll()
                val existingByPath = existing.associateBy { it.filePath }
                val knownPaths = existing.map { it.filePath }.toHashSet()
                val knownMeta = existing.map {
                    "${(it.title ?: "").lowercase()}|${(it.artist ?: "").lowercase()}|${it.durationMs / 2000}"
                }.toHashSet()
                val artDir = File(context.cacheDir, "art"); if (!artDir.exists()) artDir.mkdirs()
                for (uriStr in uris) {
                    try {
                        val df = DocumentFile.fromSingleUri(context, Uri.parse(uriStr)) ?: continue
                        if (!isAudioFile(df)) continue
                        if (knownPaths.contains(uriStr)) {
                            duplicates++; existingByPath[uriStr]?.let { playlistAttachIds.add(it.id) }; continue
                        }
                        val retriever = MediaMetadataRetriever()
                        val song = buildSong(df, uriStr, df.name ?: uriStr, df.length(), retriever, artDir)
                        try { retriever.release() } catch (_: Exception) {}
                        if (song == null) { duplicates++; continue }
                        val metaKey = "${(song.title ?: "").lowercase()}|${(song.artist ?: "").lowercase()}|${song.durationMs / 2000}"
                        if (knownMeta.contains(metaKey)) {
                            duplicates++
                            existing.firstOrNull { "${(it.title ?: "").lowercase()}|${(it.artist ?: "").lowercase()}|${it.durationMs / 2000}" == metaKey }?.let { playlistAttachIds.add(it.id) }
                            continue
                        }
                        val newId = songDao.insert(song)
                        if (newId > 0) {
                            added++; playlistAttachIds.add(newId); knownPaths.add(uriStr); knownMeta.add(metaKey)
                        } else {
                            duplicates++; songDao.getByPath(uriStr)?.let { playlistAttachIds.add(it.id) }
                        }
                    } catch (e: Exception) { e.printStackTrace() }
                }
                if (targetPlaylistId != null && playlistAttachIds.isNotEmpty()) {
                    playlistDao.addSongsToPlaylist(targetPlaylistId, playlistAttachIds.toSet().toList())
                }
            } catch (e: Exception) { e.printStackTrace() }
            ImportResult(added, duplicates, total)
        }

    private suspend fun importFolderInternal(folderUriString: String, targetPlaylistId: Long?): ImportResult =
        withContext(Dispatchers.IO) {
            var added = 0; var duplicates = 0; var total = 0
            val playlistAttachIds = mutableListOf<Long>()
            try {
                val tree = DocumentFile.fromTreeUri(context, Uri.parse(folderUriString))
                if (tree != null) {
                    val files = mutableListOf<DocumentFile>(); walkTree(tree, files, 0)
                    val audio = files.filter { isAudioFile(it) }; total = audio.size
                    val existing = songDao.listAll()
                    val existingByPath = existing.associateBy { it.filePath }
                    val knownPaths = existing.map { it.filePath }.toHashSet()
                    val knownNameSize = existing.map { "${it.fileName.lowercase()}|${it.fileSize}" }.toHashSet()
                    val knownMeta = existing.map {
                        "${(it.title ?: "").lowercase()}|${(it.artist ?: "").lowercase()}|${it.durationMs / 2000}"
                    }.toHashSet()
                    val knownFps = existing.mapNotNull { it.fingerprint }.toHashSet()
                    val artDir = File(context.cacheDir, "art"); if (!artDir.exists()) artDir.mkdirs()
                    for (df in audio) {
                        try {
                            val uriStr = df.uri.toString(); val fileName = df.name ?: uriStr; val fileSize = df.length()
                            if (knownPaths.contains(uriStr)) {
                                duplicates++; existingByPath[uriStr]?.let { playlistAttachIds.add(it.id) }; continue
                            }
                            if (knownNameSize.contains("${fileName.lowercase()}|$fileSize")) {
                                duplicates++
                                existing.firstOrNull { "${it.fileName.lowercase()}|${it.fileSize}" == "${fileName.lowercase()}|$fileSize" }?.let { playlistAttachIds.add(it.id); knownPaths.add(it.filePath) }
                                continue
                            }
                            val retriever = MediaMetadataRetriever()
                            val song = buildSong(df, uriStr, fileName, fileSize, retriever, artDir)
                            try { retriever.release() } catch (_: Exception) {}
                            if (song == null) { duplicates++; continue }
                            val metaKey = "${(song.title ?: "").lowercase()}|${(song.artist ?: "").lowercase()}|${song.durationMs / 2000}"
                            if (knownMeta.contains(metaKey)) {
                                duplicates++
                                existing.firstOrNull { "${(it.title ?: "").lowercase()}|${(it.artist ?: "").lowercase()}|${it.durationMs / 2000}" == metaKey }?.let { playlistAttachIds.add(it.id) }
                                continue
                            }
                            val fp = song.fingerprint
                            if (fp != null && knownFps.contains(fp)) {
                                duplicates++
                                existing.firstOrNull { it.fingerprint == fp }?.let { playlistAttachIds.add(it.id) }
                                continue
                            }
                            val newId = songDao.insert(song)
                            if (newId > 0) {
                                added++; playlistAttachIds.add(newId); knownPaths.add(uriStr)
                                knownNameSize.add("${fileName.lowercase()}|$fileSize"); knownMeta.add(metaKey)
                                if (fp != null) knownFps.add(fp)
                            } else {
                                duplicates++; songDao.getByPath(uriStr)?.let { playlistAttachIds.add(it.id) }
                            }
                        } catch (e: Exception) { e.printStackTrace() }
                    }
                    if (targetPlaylistId != null && playlistAttachIds.isNotEmpty()) {
                        playlistDao.addSongsToPlaylist(targetPlaylistId, playlistAttachIds.toSet().toList())
                    }
                }
            } catch (e: Exception) { e.printStackTrace() }
            ImportResult(added, duplicates, total)
        }

    /** Reads metadata + embedded art + fingerprint. Returns null if the file is rejected. */
    private fun buildSong(
        df: DocumentFile,
        uriStr: String,
        fileName: String,
        fileSize: Long,
        retriever: MediaMetadataRetriever,
        artDir: File
    ): Song? {
        return try {
            retriever.setDataSource(context, df.uri)
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            // Reject silence/broken files.
            if (durationMs <= 0) return null

            val title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                ?: fileName.substringBeforeLast('.')
            val artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                ?: retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST)
            val album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
            val trackNo = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)
                ?.substringBefore('/')?.toIntOrNull()
            val year = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_YEAR)?.toIntOrNull()
            val mime = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE) ?: df.type

            // Embedded art → cacheDir/art/art_{hash}.jpg
            var artUri: String? = null
            val art = try {
                retriever.embeddedPicture
            } catch (e: Exception) {
                null
            }
            if (art != null && art.isNotEmpty()) {
                try {
                    val artFile = File(artDir, "art_${pathHash(uriStr)}.jpg")
                    if (!artFile.exists()) {
                        FileOutputStream(artFile).use { it.write(art) }
                    }
                    artUri = artFile.absolutePath
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            val fingerprint = try {
                context.contentResolver.openInputStream(df.uri)?.use {
                    Fingerprinter.fromStream(it, fileSize)
                }
            } catch (e: Exception) {
                null
            }

            Song(
                filePath = uriStr,
                fileName = fileName,
                fileSize = fileSize,
                title = title,
                artist = artist,
                album = album,
                durationMs = durationMs,
                trackNo = trackNo,
                year = year,
                mimeType = mime,
                dateAddedSec = System.currentTimeMillis() / 1000,
                fingerprint = fingerprint,
                artUri = artUri,
                lastModified = df.lastModified()
            )
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun walkTree(root: DocumentFile, out: MutableList<DocumentFile>, depth: Int) {
        if (depth > 8) return
        try {
            for (child in root.listFiles()) {
                if (child.isDirectory) {
                    walkTree(child, out, depth + 1)
                } else {
                    out.add(child)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun isAudioFile(df: DocumentFile): Boolean {
        val name = df.name?.lowercase() ?: return false
        val type = df.type?.lowercase()
        if (type != null && type.startsWith("audio/")) return true
        return name.endsWith(".mp3") ||
            name.endsWith(".m4a") ||
            name.endsWith(".aac") ||
            name.endsWith(".ogg") ||
            name.endsWith(".wav") ||
            name.endsWith(".flac") ||
            name.endsWith(".opus")
    }

    private fun pathHash(key: String): String {
        return try {
            val md = MessageDigest.getInstance("SHA-1")
            md.digest(key.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            key.hashCode().toUInt().toString()
        }
    }
}
