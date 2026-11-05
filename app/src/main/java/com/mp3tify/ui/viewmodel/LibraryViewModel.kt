package com.mp3tify.ui.viewmodel

import com.mp3tify.data.model.Playlist
import com.mp3tify.data.model.Song
import com.mp3tify.data.repository.Repository
import com.mp3tify.playback.PlaybackController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModel(
    private val repo: Repository = Repository.get()
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    val sortOrder = MutableStateFlow(Repository.SortOrder.RECENTLY_ADDED)

    val songs: StateFlow<List<Song>> = sortOrder
        .flatMapLatest { order -> repo.observeSongsSorted(order) }
        .stateIn(scope, SharingStarted.Lazily, emptyList())

    val favorites: StateFlow<List<Song>> = repo.observeFavorites()
        .stateIn(scope, SharingStarted.Lazily, emptyList())

    val playlists: StateFlow<List<Playlist>> = repo.playlistsFlow()
        .stateIn(scope, SharingStarted.Lazily, emptyList())

    val importing = MutableStateFlow(false)
    val importProgress = MutableStateFlow(0)
    val importMessage = MutableStateFlow<String?>(null)

    val selectionMode = MutableStateFlow(false)
    val selectedIds = MutableStateFlow<Set<Long>>(emptySet())

    init {
        scope.launch {
            try {
                repo.ensureDefaultPlaylists()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun setSortOrder(order: Repository.SortOrder) {
        sortOrder.value = order
    }

    fun importFromFolder(uri: String, onDone: (Repository.ImportResult?) -> Unit = {}) {
        importing.value = true
        importProgress.value = 0
        // Timer de progreso: sube gradualmente hasta 95% mientras el import corre.
        val progressJob = scope.launch {
            while (importing.value) {
                if (importProgress.value < 95) importProgress.value += 1
                kotlinx.coroutines.delay(200)
            }
        }
        scope.launch(Dispatchers.IO) {
            var result: Repository.ImportResult? = null
            try {
                result = repo.importFromFolder(uri)
            } catch (e: Exception) {
                e.printStackTrace()
            }
            progressJob.cancel()
            importProgress.value = 100
            importing.value = false
            try {
                onDone(result)
            } catch (e: Exception) {
                e.printStackTrace()
            }
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
            try { result = repo.importFiles(uris) } catch (e: Exception) { e.printStackTrace() }
            progressJob.cancel()
            importProgress.value = 100
            importing.value = false
            try { onDone(result) } catch (e: Exception) { e.printStackTrace() }
        }
    }

    fun importFilesIntoPlaylist(uris: List<String>, playlistId: Long, onDone: (Repository.ImportResult?) -> Unit = {}) {
        importing.value = true
        scope.launch(Dispatchers.IO) {
            var result: Repository.ImportResult? = null
            try { result = repo.importFiles(uris, playlistId) } catch (e: Exception) { e.printStackTrace() }
            importing.value = false
            try { onDone(result) } catch (e: Exception) { e.printStackTrace() }
        }
    }

    fun importFolderIntoPlaylist(uri: String, playlistId: Long, onDone: (Repository.ImportResult?) -> Unit = {}) {
        importing.value = true
        scope.launch(Dispatchers.IO) {
            var result: Repository.ImportResult? = null
            try {
                result = repo.importFolderIntoPlaylist(uri, playlistId)
            } catch (e: Exception) {
                e.printStackTrace()
            }
            importing.value = false
            try {
                onDone(result)
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

    fun toggleSelected(songId: Long) {
        val current = selectedIds.value.toMutableSet()
        if (current.contains(songId)) {
            current.remove(songId)
        } else {
            current.add(songId)
        }
        selectedIds.value = current
        selectionMode.value = current.isNotEmpty()
    }

    fun exitSelectionMode() {
        selectedIds.value = emptySet()
        selectionMode.value = false
    }

    fun createPlaylist(
        name: String,
        songIds: List<Long>,
        folderUri: String? = null,
        onCreated: (Long) -> Unit = {}
    ) {
        // Si hay folderUri, activar importing + progress para que el overlay
        // de "Cargando archivos..." se muestre mientras se importa la carpeta.
        if (!folderUri.isNullOrBlank()) {
            importing.value = true
            importProgress.value = 0
        }
        val progressJob = if (!folderUri.isNullOrBlank()) scope.launch {
            while (importing.value) {
                if (importProgress.value < 95) importProgress.value += 1
                kotlinx.coroutines.delay(200)
            }
        } else null
        scope.launch {
            try {
                val id = repo.createPlaylist(name, null, folderUri)
                if (id > 0) {
                    if (songIds.isNotEmpty()) repo.addSongsToPlaylist(id, songIds)
                    if (!folderUri.isNullOrBlank()) {
                        scope.launch(Dispatchers.IO) {
                            try { repo.importFolderIntoPlaylist(folderUri, id) } catch (e: Exception) { e.printStackTrace() }
                            progressJob?.cancel()
                            importProgress.value = 100
                            importing.value = false
                        }
                    }
                }
                onCreated(id)
            } catch (e: Exception) {
                e.printStackTrace()
                progressJob?.cancel()
                importing.value = false
            }
        }
    }

    fun addSongsToExistingPlaylist(playlistId: Long, songIds: List<Long>) {
        scope.launch {
            try {
                repo.addSongsToPlaylist(playlistId, songIds)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun setPlaylistFolder(playlistId: Long, folderUri: String) {
        scope.launch {
            try {
                repo.setPlaylistFolder(playlistId, folderUri)
            } catch (e: Exception) {
                e.printStackTrace()
            }
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

    fun deleteSong(song: Song) {
        scope.launch {
            try {
                repo.deleteSongById(song.id)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun updateSongInfo(song: Song, title: String?, artist: String?, album: String?) {
        scope.launch {
            try {
                repo.updateSongInfo(song.id, title, artist, album)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}
