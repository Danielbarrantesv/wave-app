package com.mp3tify.ui.screens.nowplaying
import com.mp3tify.data.model.displayArtist
import com.mp3tify.data.model.displayTitle

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.VolumeDown
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mp3tify.data.repository.Repository
import com.mp3tify.playback.PlaybackController
import com.mp3tify.ui.components.AlbumArt
import com.mp3tify.ui.components.formatDuration
import com.mp3tify.ui.t
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

@Composable
fun NowPlayingScreen(
    onBack: () -> Unit,
    onOpenQueue: () -> Unit,
    onOpenLyrics: () -> Unit,
    onOpenEqualizer: () -> Unit = {}
) {
    val context = LocalContext.current
    val state by PlaybackController.state.collectAsState()
    val scope = rememberCoroutineScope()
    val current = state.playlist.getOrNull(state.currentWindow)

    // Library + favorites observer (also used for the empty-state CTA).
    val repo = remember { Repository.get(context) }
    val librarySongs by repo.observeSongs().collectAsState(initial = emptyList())

    // CRITICAL FIX (Bug corazón): look up the current song in the DB-observed
    // library list so isFavorite updates reactively after toggleFavorite().
    val currentFromDb = if (current != null) librarySongs.firstOrNull { it.id == current.id } else null
    val effectiveCurrent = currentFromDb ?: current

    var localSeek by remember { mutableStateOf<Long?>(null) }

    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            GlobalScope.launch(Dispatchers.IO) {
                try {
                    Repository.get(context).importFromFolder(uri.toString())
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    val audioManager = remember {
        context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    }
    val maxVolume = remember {
        (audioManager?.getStreamMaxVolume(AudioManager.STREAM_MUSIC)?.toFloat()) ?: 15f
    }
    var volumeValue by remember {
        mutableStateOf(
            ((audioManager?.getStreamVolume(AudioManager.STREAM_MUSIC)?.toFloat()) ?: 8f) / maxVolume
        )
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = t("back"))
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onOpenEqualizer) {
                    Icon(Icons.Filled.Equalizer, contentDescription = t("equalizer"))
                }
                IconButton(onClick = onOpenLyrics) {
                    Icon(Icons.Filled.Description, contentDescription = t("lyrics"))
                }
                IconButton(onClick = onOpenQueue) {
                    Icon(Icons.Filled.QueueMusic, contentDescription = t("queue"))
                }
            }
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (current == null) {
                // ---------------- EMPTY STATE ----------------
                Spacer(Modifier.weight(1f))
                Icon(
                    Icons.Filled.MusicNote,
                    contentDescription = null,
                    modifier = Modifier.size(96.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    if (librarySongs.isEmpty()) t("add_songs_cta") else t("play_something"),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    if (librarySongs.isEmpty()) t("add_songs_cta_hint") else t("play_something_hint"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(24.dp))
                if (librarySongs.isEmpty()) {
                    Button(onClick = { folderPicker.launch(null) }) {
                        Icon(Icons.Filled.Folder, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text(t("add_music"))
                    }
                } else {
                    Button(onClick = {
                        val songs = librarySongs
                        if (songs.isNotEmpty()) {
                            val pick = songs.random()
                            PlaybackController.setQueue(songs, songs.indexOf(pick))
                        }
                    }) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text(t("play_all"))
                    }
                }
                Spacer(Modifier.weight(1f))
            } else {
                // ---------------- NORMAL STATE ----------------
                Spacer(Modifier.height(8.dp))
                AlbumArt(song = current, size = 360.dp)
                Spacer(Modifier.height(24.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            current.displayTitle,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            current.displayArtist,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    IconButton(onClick = {
                        scope.launch {
                            try { repo.toggleFavorite(current) } catch (e: Exception) { e.printStackTrace() }
                        }
                    }) {
                        val isFav = effectiveCurrent?.isFavorite == true
                        Icon(
                            if (isFav) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                            contentDescription = if (isFav) t("remove_from_favorites") else t("add_to_favorites"),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))

                val displayPosition = localSeek ?: state.positionMs
                Slider(
                    value = if (state.durationMs > 0) {
                        (displayPosition.toFloat() / state.durationMs).coerceIn(0f, 1f)
                    } else {
                        0f
                    },
                    onValueChange = { v -> localSeek = (v * state.durationMs).toLong() },
                    onValueChangeFinished = {
                        localSeek?.let { PlaybackController.seekTo(it) }
                        localSeek = null
                    },
                    colors = SliderDefaults.colors(
                        thumbColor = MaterialTheme.colorScheme.primary,
                        activeTrackColor = MaterialTheme.colorScheme.primary,
                        inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                )
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        formatDuration(displayPosition),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        formatDuration(state.durationMs),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(Modifier.height(16.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    IconButton(
                        onClick = { PlaybackController.skipToPrevious() },
                        modifier = Modifier.size(56.dp)
                    ) {
                        Icon(
                            Icons.Filled.SkipPrevious,
                            contentDescription = t("previous"),
                            modifier = Modifier.size(40.dp)
                        )
                    }
                    Surface(
                        onClick = {
                            if (state.isPlaying) PlaybackController.pause() else PlaybackController.play()
                        },
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(72.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                contentDescription = t("pause"),
                                tint = Color.Black,
                                modifier = Modifier.size(40.dp)
                            )
                        }
                    }
                    IconButton(
                        onClick = { PlaybackController.skipToNext() },
                        modifier = Modifier.size(56.dp)
                    ) {
                        Icon(
                            Icons.Filled.SkipNext,
                            contentDescription = t("next"),
                            modifier = Modifier.size(40.dp)
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))

                // Volume row wired to AudioManager STREAM_MUSIC.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.VolumeDown,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Slider(
                        value = volumeValue,
                        onValueChange = { v ->
                            volumeValue = v
                            try {
                                audioManager?.setStreamVolume(
                                    AudioManager.STREAM_MUSIC,
                                    (v * maxVolume).toInt().coerceIn(0, maxVolume.toInt()),
                                    0
                                )
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                        },
                        modifier = Modifier.weight(1f)
                    )
                    Icon(
                        Icons.Filled.VolumeUp,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(20.dp))

                // Bottom action row: lyrics / queue / shuffle / repeat.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onOpenLyrics) {
                        Icon(Icons.Filled.Description, contentDescription = t("lyrics"), tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(28.dp))
                    }
                    IconButton(onClick = onOpenQueue) {
                        Icon(Icons.Filled.QueueMusic, contentDescription = t("queue"), tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(28.dp))
                    }
                    IconButton(onClick = { PlaybackController.setShuffle(!state.shuffle) }) {
                        Icon(Icons.Filled.Shuffle, contentDescription = t("shuffle"),
                            tint = if (state.shuffle) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(28.dp))
                    }
                    IconButton(onClick = { PlaybackController.setRepeatOne(!state.repeatOne) }) {
                        Icon(if (state.repeatOne) Icons.Filled.RepeatOne else Icons.Filled.Repeat,
                            contentDescription = t("repeat"),
                            tint = if (state.repeatOne) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(28.dp))
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}
