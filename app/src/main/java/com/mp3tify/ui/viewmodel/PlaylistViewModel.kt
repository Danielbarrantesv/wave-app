package com.mp3tify.ui.viewmodel

import android.content.Context
import com.mp3tify.data.model.Playlist
import com.mp3tify.data.model.Song
import com.mp3tify.data.model.displayArtist
import com.mp3tify.data.model.displayTitle
import com.mp3tify.data.repository.Repository
import com.mp3tify.playback.PlaybackController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class PlaylistViewModel(
    ctx: Context? = null,
    private val playlistId: Long,
    private val repo: Repository = if (ctx != null) Repository.get(ctx) else Repository.get()
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    val playlist: StateFlow<Playlist?> = repo.observePlaylist(playlistId)
        .stateIn(scope, SharingStarted.Lazily, null)

    // #3 Sort filter en playlist: 5 opciones (mismas que LibraryScreen).
    val sortOrder = MutableStateFlow(Repository.SortOrder.RECENTLY_ADDED)

    /**
     * Songs observadas con el sort aplicado. Por defecto RECENTLY_ADDED (mantiene
     * el orden de la playlist por position field; si se cambia a TITLE/ARTIST/etc.
     * se ordena según ese criterio ignorando el position).
     */
    val songs: StateFlow<List<Song>> = combine(
        repo.observePlaylistSongs(playlistId),
        sortOrder
    ) { playlistSongs, order ->
        when (order) {
            Repository.SortOrder.RECENTLY_ADDED -> playlistSongs // respeta posición original
            Repository.SortOrder.RECENTLY_PLAYED -> playlistSongs.sortedByDescending { it.lastPlayedAt }
            Repository.SortOrder.TITLE -> playlistSongs.sortedBy { it.displayTitle.lowercase() }
            Repository.SortOrder.ARTIST -> playlistSongs.sortedBy { it.displayArtist.lowercase() }
            Repository.SortOrder.DURATION -> playlistSongs.sortedByDescending { it.durationMs }
        }
    }.stateIn(scope, SharingStarted.Lazily, emptyList())

    val importing = MutableStateFlow(false)
    val importProgress = MutableStateFlow(0)

    // Multi-select mode (#1 SelectionBanner en playlist)
    val selectionMode = MutableStateFlow(false)
    val selectedIds = MutableStateFlow<Set<Long>>(emptySet())

    fun toggleSelected(songId: Long) {
        val current = selectedIds.value.toMutableSet()
        if (!current.add(songId)) current.remove(songId)
        selectedIds.value = current
        selectionMode.value = current.isNotEmpty()
    }

    fun exitSelectionMode() {
        selectedIds.value = emptySet()
        selectionMode.value = false
    }

    /** Cambia el criterio de orden de las canciones dentro de la playlist (#3). */
    fun setSortOrder(order: Repository.SortOrder) {
        sortOrder.value = order
    }

    /** Reproduce solo las canciones seleccionadas en orden. */
    fun playSelected() {
        try {
            val all = songs.value
            val list = all.filter { it.id in selectedIds.value }
            if (list.isNotEmpty()) {
                PlaybackController.setShuffle(false)
                PlaybackController.setQueue(list, 0)
                exitSelectionMode()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /** Quita las canciones seleccionadas de esta playlist (no las borra de la biblioteca). */
    fun removeSelectedFromPlaylist() {
        scope.launch {
            try {
                val ids = selectedIds.value.toList()
                ids.forEach { id -> repo.removeSongFromPlaylist(playlistId, id) }
                exitSelectionMode()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    /** Elimina las canciones seleccionadas de la biblioteca completa. */
    fun deleteSelectedFromLibrary() {
        scope.launch {
            try {
                val ids = selectedIds.value.toList()
                ids.forEach { id -> repo.deleteSongById(id) }
                exitSelectionMode()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun play(song: Song) {
        try {
            val list = songs.value
            val idx = list.indexOfFirst { it.id == song.id }
            if (idx >= 0) {
                PlaybackController.setQueue(list, idx)
            } else {
                PlaybackController.setQueue(listOf(song), 0)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun playAll(shuffle: Boolean) {
        try {
            val list = songs.value
            if (list.isEmpty()) return
            if (shuffle) {
                PlaybackController.setShuffle(true)
                PlaybackController.setQueue(list, (0 until list.size).random())
            } else {
                PlaybackController.setShuffle(false)
                PlaybackController.setQueue(list, 0)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun toggleFavorite(song: Song) {
        scope.launch {
            try {
                repo.toggleFavorite(song)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun removeSongFromPlaylist(song: Song) {
        scope.launch {
            try {
                repo.removeSongFromPlaylist(playlistId, song.id)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun setFolder(folderUri: String) {
        scope.launch {
            try {
                repo.setPlaylistFolder(playlistId, folderUri)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun importFolder(uri: String, onDone: (Repository.ImportResult?) -> Unit = {}) {
        importing.value = true
        importProgress.value = 0
        val progressJob = scope.launch {
            while (importing.value) {
                if (importProgress.value < 95) importProgress.value += 1
                kotlinx.coroutines.delay(200)
            }
        }
        scope.launch(Dispatchers.IO) {
            var result: Repository.ImportResult? = null
            try { result = repo.importFolderIntoPlaylist(uri, playlistId) } catch (e: Exception) { e.printStackTrace() }
            progressJob.cancel()
            importProgress.value = 100
            importing.value = false
            try { onDone(result) } catch (e: Exception) { e.printStackTrace() }
        }
    }

    fun importFiles(uris: List<String>, onDone: (Repository.ImportResult?) -> Unit = {}) {
        importing.value = true
        importProgress.value = 0
        val progressJob = scope.launch {
            while (importing.value) {
                if (importProgress.value < 95) importProgress.value += 1
                kotlinx.coroutines.delay(200)
            }
        }
        scope.launch(Dispatchers.IO) {
            var result: Repository.ImportResult? = null
            try { result = repo.importFiles(uris, playlistId) } catch (e: Exception) { e.printStackTrace() }
            progressJob.cancel()
            importProgress.value = 100
            importing.value = false
            try { onDone(result) } catch (e: Exception) { e.printStackTrace() }
        }
    }

    fun rename(newName: String) {
        scope.launch {
            try {
                repo.renamePlaylist(playlistId, newName)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun delete(onDone: () -> Unit = {}) {
        scope.launch {
            try {
                repo.deletePlaylist(playlistId)
            } catch (e: Exception) {
                e.printStackTrace()
            }
            try {
                onDone()
            } catch (e: Exception) {
            }
        }
    }
}
