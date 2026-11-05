package com.mp3tify.ui.viewmodel

import android.content.Context
import com.mp3tify.data.model.Playlist
import com.mp3tify.data.model.Song
import com.mp3tify.data.repository.Repository
import com.mp3tify.playback.PlaybackController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class PlaylistViewModel(
    ctx: Context? = null,
    private val playlistId: Long,
    private val repo: Repository = if (ctx != null) Repository.get(ctx) else Repository.get()
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    val playlist: StateFlow<Playlist?> = repo.observePlaylist(playlistId)
        .stateIn(scope, SharingStarted.Lazily, null)

    val songs: StateFlow<List<Song>> = repo.observePlaylistSongs(playlistId)
        .stateIn(scope, SharingStarted.Lazily, emptyList())

    val importing = MutableStateFlow(false)
    val importProgress = MutableStateFlow(0)

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
