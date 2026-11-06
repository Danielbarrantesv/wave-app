package com.mp3tify.ui.screens.nowplaying
import com.mp3tify.data.model.displayArtist
import com.mp3tify.data.model.displayTitle

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mp3tify.data.repository.Repository
import com.mp3tify.playback.PlaybackController
import com.mp3tify.ui.components.AlbumArtCache
import com.mp3tify.ui.components.formatDuration
import com.mp3tify.ui.t
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * NowPlayingScreen — full-screen player.
 *
 * Layout:
 *   - Album cover as BACKGROUND (fillMaxSize, contentScale = Crop)
 *   - Dark scrim overlay (Color.Black.copy(alpha = 0.55f)) so all icons are visible
 *   - Content centered vertically and horizontally
 *   - Icons smaller (24-28dp) and white-ish tint for contrast against the scrim
 *   - In landscape, verticalScroll enabled so content that doesn't fit can scroll
 *
 * The album cover bitmap is loaded via AlbumArtCache.decode(filePath, 512) for
 * higher resolution. If decode fails (no ID3 art, file missing), a plain dark
 * background is shown.
 */
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

    // Background album cover bitmap (higher-res 512 for the full-screen bg).
    var bgBitmap by remember(current?.id) { mutableStateOf<android.graphics.Bitmap?>(null) }
    androidx.compose.runtime.LaunchedEffect(current?.id) {
        val s = current ?: return@LaunchedEffect
        bgBitmap = withContext(Dispatchers.IO) {
            try {
                // Try cached JPG first (faster)
                val artPath = s.artUri?.takeIf { it.isNotBlank() }
                if (artPath != null && artPath.startsWith("/") && java.io.File(artPath).exists()) {
                    android.graphics.BitmapFactory.decodeFile(artPath)
                } else {
                    // Fall back to decoding ID3 from the audio file
                    AlbumArtCache.decode(context, s.filePath, 512)
                }
            } catch (e: Exception) {
                null
            }
        }
    }

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

    val scrollState = rememberScrollState()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // ===== BACKGROUND: album cover full-screen =====
        val bmp = bgBitmap
        if (bmp != null) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
        // Dark scrim overlay so all icons/text are visible against the cover.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.55f))
        )

        // ===== CONTENT =====
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .windowInsetsPadding(WindowInsets.statusBars)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (current == null) {
                // ---------------- EMPTY STATE ----------------
                Spacer(Modifier.height(80.dp))
                Icon(
                    Icons.Filled.MusicNote,
                    contentDescription = null,
                    modifier = Modifier.size(96.dp),
                    tint = Color.White
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    if (librarySongs.isEmpty()) t("add_songs_cta") else t("play_something"),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    color = Color.White
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    if (librarySongs.isEmpty()) t("add_songs_cta_hint") else t("play_something_hint"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.8f),
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
                Spacer(Modifier.height(40.dp))
            } else {
                // ---------------- NORMAL STATE ----------------
                // Top row: back (left) + EQ/lyrics/queue (right) — smaller icons, white tint
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onBack, modifier = Modifier.size(40.dp)) {
                        Icon(
                            Icons.Filled.ArrowBack,
                            contentDescription = t("back"),
                            tint = Color.White,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = onOpenEqualizer, modifier = Modifier.size(40.dp)) {
                        Icon(
                            Icons.Filled.Equalizer,
                            contentDescription = t("equalizer"),
                            tint = Color.White,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    IconButton(onClick = onOpenLyrics, modifier = Modifier.size(40.dp)) {
                        Icon(
                            Icons.Filled.Description,
                            contentDescription = t("lyrics"),
                            tint = Color.White,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    IconButton(onClick = onOpenQueue, modifier = Modifier.size(40.dp)) {
                        Icon(
                            Icons.Filled.QueueMusic,
                            contentDescription = t("queue"),
                            tint = Color.White,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }

                Spacer(Modifier.height(24.dp))

                // Title + artist + heart, centered
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        current.displayTitle,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                        color = Color.White
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        current.displayArtist,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.85f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(12.dp))
                    IconButton(
                        onClick = {
                            scope.launch {
                                try {
                                    repo.toggleFavorite(effectiveCurrent ?: current)
                                } catch (e: Exception) { e.printStackTrace() }
                            }
                        },
                        modifier = Modifier.size(40.dp)
                    ) {
                        val isFav = effectiveCurrent?.isFavorite == true
                        Icon(
                            if (isFav) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                            contentDescription = if (isFav) t("remove_from_favorites") else t("add_to_favorites"),
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))

                // Progress slider + times
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
                        thumbColor = Color.White,
                        activeTrackColor = Color.White,
                        inactiveTrackColor = Color.White.copy(alpha = 0.3f)
                    )
                )
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        formatDuration(displayPosition),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.85f)
                    )
                    Text(
                        formatDuration(state.durationMs),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.85f)
                    )
                }

                Spacer(Modifier.height(16.dp))

                // Main playback controls: prev / play-pause / next
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    IconButton(
                        onClick = { PlaybackController.skipToPrevious() },
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(
                            Icons.Filled.SkipPrevious,
                            contentDescription = t("previous"),
                            tint = Color.White,
                            modifier = Modifier.size(36.dp)
                        )
                    }
                    Surface(
                        onClick = {
                            if (state.isPlaying) PlaybackController.pause() else PlaybackController.play()
                        },
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(64.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                contentDescription = t("pause"),
                                tint = Color.Black,
                                modifier = Modifier.size(36.dp)
                            )
                        }
                    }
                    IconButton(
                        onClick = { PlaybackController.skipToNext() },
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(
                            Icons.Filled.SkipNext,
                            contentDescription = t("next"),
                            tint = Color.White,
                            modifier = Modifier.size(36.dp)
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))

                // Volume row
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.VolumeDown,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.85f),
                        modifier = Modifier.size(22.dp)
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
                        modifier = Modifier.weight(1f),
                        colors = SliderDefaults.colors(
                            thumbColor = Color.White,
                            activeTrackColor = Color.White,
                            inactiveTrackColor = Color.White.copy(alpha = 0.3f)
                        )
                    )
                    Icon(
                        Icons.Filled.VolumeUp,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.85f),
                        modifier = Modifier.size(22.dp)
                    )
                }
                Spacer(Modifier.height(16.dp))

                // Bottom action row: lyrics / queue / shuffle / repeat — smaller icons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onOpenLyrics, modifier = Modifier.size(40.dp)) {
                        Icon(
                            Icons.Filled.Description,
                            contentDescription = t("lyrics"),
                            tint = Color.White.copy(alpha = 0.9f),
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    IconButton(onClick = onOpenQueue, modifier = Modifier.size(40.dp)) {
                        Icon(
                            Icons.Filled.QueueMusic,
                            contentDescription = t("queue"),
                            tint = Color.White.copy(alpha = 0.9f),
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    IconButton(onClick = { PlaybackController.setShuffle(!state.shuffle) }, modifier = Modifier.size(40.dp)) {
                        Icon(
                            Icons.Filled.Shuffle,
                            contentDescription = t("shuffle"),
                            tint = if (state.shuffle) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.9f),
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    IconButton(onClick = { PlaybackController.setRepeatOne(!state.repeatOne) }, modifier = Modifier.size(40.dp)) {
                        Icon(
                            if (state.repeatOne) Icons.Filled.RepeatOne else Icons.Filled.Repeat,
                            contentDescription = t("repeat"),
                            tint = if (state.repeatOne) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.9f),
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
                Spacer(Modifier.height(20.dp))
            }
        }
    }
}
