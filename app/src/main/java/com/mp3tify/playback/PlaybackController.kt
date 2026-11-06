package com.mp3tify.playback

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.mp3tify.data.model.Song
import com.mp3tify.data.model.displayAlbum
import com.mp3tify.data.model.displayArtist
import com.mp3tify.data.model.displayTitle
import com.mp3tify.data.repository.Repository
import com.mp3tify.equalizer.EqualizerManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class PlaybackState(
    val isPlaying: Boolean = false,
    val currentWindow: Int = -1,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val playlist: List<Song> = emptyList(),
    val hasNext: Boolean = false,
    val hasPrev: Boolean = false,
    val shuffle: Boolean = false,
    val repeatOne: Boolean = false
)

object PlaybackController {

    private var player: Player? = null
    private var appCtx: Context? = null
    private val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow(PlaybackState())
    val state: StateFlow<PlaybackState> = _state.asStateFlow()

    private var pollJob: Job? = null
    private var lastRecordedWindow = -1
    private var volumeBoost = 1.0f

    /**
     * CRITICAL (Bug EQ no funciona): audio session ID que seteamos en ExoPlayer durante connect().
     * ExoPlayer.audioSessionId puede devolver 0 antes del primer playback aunque hayamos llamado
     * a setAudioSessionId(). Si devolvemos 0, EqualizerManager cae al fallback de generar otra
     * sesión distinta y el EQ no afecta el audio real. Guardamos el ID que seteamos y lo
     * devolvemos directamente; si ExoPlayer reporta uno distinto tras el primer playback,
     * lo sincronizamos en refreshState().
     */
    @Volatile
    private var committedAudioSessionId: Int = 0

    /** Exposes the shared player to MediaPlaybackService (MediaSession). */
    fun playerOrNull(): Player? = player

    fun connect(context: Context) {
        try {
            // CRITICAL FIX (Bug "ya no reproduce después de un rato"): antes este método
            // hacia `if (player != null) return` sin verificar que el player estuviera
            // vivo. Si el sistema mataba el ExoPlayer tras un rato de inactividad pero
            // el singleton conservaba la referencia, connect() salía sin reconectar.
            // Resultado: el usuario abría Wave, intentaba reproducir, y nada sonaba.
            // Tenía que desinstalar y reinstalar para que el singleton se recreara.
            //
            // Ahora: sondar el player existente con playbackState. Si lanza excepción
            // (player released), liberar la referencia y reconectar desde cero.
            val existing = player
            if (existing != null) {
                try {
                    @Suppress("UNUSED_VARIABLE")
                    val state = existing.playbackState
                    Log.i("PlaybackController", "connect: existing player is alive (state=$state), reusing")
                    return  // player sigue vivo, no reconectar
                } catch (e: Exception) {
                    Log.w("PlaybackController", "connect: existing player is DEAD, releasing and reconnecting", e)
                    try { existing.release() } catch (_: Exception) {}
                    player = null
                    committedAudioSessionId = 0
                    // continúa abajo para reconectar
                }
            }
            appCtx = context.applicationContext
            try {
                val serviceIntent = Intent(context.applicationContext, MediaPlaybackService::class.java)
                // CRITICAL (#2 MediaStyle notification): startService en lugar de
                // startForegroundService porque Media3 sólo llama startForeground() cuando
                // hay playback activo. Si usamos startForegroundService y no hay playback
                // en 5 segundos, Android mata el service con
                // "Context.startForegroundService() did not then call Service.startForeground()".
                // Como connect() se llama desde MainActivity.onCreate (foreground), startService
                // funciona. Media3 promote el service a foreground automáticamente cuando
                // el player state cambia a playing/buffering con media items.
                context.applicationContext.startService(serviceIntent)
            } catch (e: Exception) { e.printStackTrace() }

            val attrs = AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .build()
            val p = ExoPlayer.Builder(context.applicationContext)
                .setAudioAttributes(attrs, true)
                .setSeekBackIncrementMs(10_000L)
                .setSeekForwardIncrementMs(10_000L)
                .build()
            p.repeatMode = Player.REPEAT_MODE_OFF
            try {
                val am = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                val sessionId = am?.generateAudioSessionId() ?: 0
                if (sessionId != 0) {
                    (p as? ExoPlayer)?.setAudioSessionId(sessionId)
                    // CRITICAL (Bug EQ no funciona): guardar el ID que seteamos para que
                    // getAudioSessionId() lo devuelva aunque ExoPlayer.audioSessionId aún
                    // reporte 0 antes del primer playback.
                    committedAudioSessionId = sessionId
                }
            } catch (e: Exception) { e.printStackTrace() }

            p.addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    refreshState()
                    if (isPlaying) startPolling() else stopPolling()
                }
                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    refreshState()
                    maybeRecordPlay()
                }
                override fun onPlaybackStateChanged(playbackState: Int) { refreshState() }
                override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
                    _state.value = _state.value.copy(shuffle = shuffleModeEnabled)
                }
                override fun onRepeatModeChanged(repeatMode: Int) {
                    _state.value = _state.value.copy(repeatOne = repeatMode == Player.REPEAT_MODE_ONE)
                }
                override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                    error.printStackTrace()
                    refreshState()
                }
                // CRITICAL (Bug EQ no funciona): ExoPlayer lanza este callback cuando
                // realmente allocar/cambia su audio session. setAudioSessionId() en
                // connect() es solo un hint; ExoPlayer puede allocar su propia sesión
                // al primer playback. Este callback nos da el sessionId REAL que
                // ExoPlayer está usando para output de audio. Attachamos el EQ a esa
                // sesión para que efectivamente procese el audio que sale.
                override fun onAudioSessionIdChanged(audioSessionId: Int) {
                    Log.i("PlaybackController", "onAudioSessionIdChanged: $audioSessionId (was $committedAudioSessionId)")
                    if (audioSessionId != 0 && audioSessionId != committedAudioSessionId) {
                        committedAudioSessionId = audioSessionId
                        EqualizerManager.reattachToSession(audioSessionId)
                    }
                }
            })
            player = p
            // Trigger initial EQ attach if ExoPlayer reports a non-zero session immediately
            // (some devices allocate the session on creation, before any playback).
            try {
                val initialSid = (p as? ExoPlayer)?.audioSessionId ?: 0
                if (initialSid != 0) {
                    Log.i("PlaybackController", "connect: initial audioSessionId = $initialSid")
                    committedAudioSessionId = initialSid
                    EqualizerManager.reattachToSession(initialSid)
                }
            } catch (e: Exception) { e.printStackTrace() }
            refreshState()
        } catch (e: Exception) { e.printStackTrace() }
    }

    /**
     * CRITICAL FIX (Bug repro muere): ensures the player is connected AND alive.
     * If the player was released by the system after a long idle period, reading
     * playbackState throws. We probe; if it throws, release + reconnect.
     */
    private fun ensureConnected(): Player? {
        val p = player
        if (p != null) {
            try {
                @Suppress("UNUSED_VARIABLE")
                val state = p.playbackState
                return p
            } catch (e: Exception) {
                e.printStackTrace()
                try { p.release() } catch (_: Exception) {}
                player = null
            }
        }
        val ctx = appCtx ?: return null
        try { connect(ctx) } catch (e: Exception) { e.printStackTrace() }
        return player
    }

    private fun refreshState() {
        val p = player ?: return
        try {
            val window = p.currentMediaItemIndex
            _state.value = _state.value.copy(
                isPlaying = p.isPlaying,
                currentWindow = if (p.mediaItemCount == 0) -1 else window,
                positionMs = p.currentPosition.coerceAtLeast(0L),
                durationMs = p.duration.coerceAtLeast(0L),
                hasNext = p.hasNextMediaItem(),
                hasPrev = p.hasPreviousMediaItem(),
                shuffle = p.shuffleModeEnabled,
                repeatOne = p.repeatMode == Player.REPEAT_MODE_ONE
            )
            // EQ attach ahora se hace via Player.Listener.onAudioSessionIdChanged (ver connect()).
            // Ya no hacemos polling aquí.
        } catch (e: Exception) {
            e.printStackTrace()
            stopPolling()
        }
    }

    private fun startPolling() {
        if (pollJob?.isActive == true) return
        pollJob = mainScope.launch {
            while (isActive) {
                refreshState()
                // #3 Optimización segundo plano: 500ms en lugar de 200ms reduce CPU/battery
                // usage durante playback prolongado sin afectar UX visible (la barra de
                // progreso aún se actualiza 2x por segundo, suficiente para sentir smooth).
                delay(500)
            }
        }
    }
    private fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    private fun maybeRecordPlay() {
        try {
            val p = player ?: return
            val window = p.currentMediaItemIndex
            if (window == lastRecordedWindow) return
            lastRecordedWindow = window
            val song = _state.value.playlist.getOrNull(window) ?: return
            ioScope.launch {
                try { Repository.get().recordPlay(song.id) } catch (e: Exception) { e.printStackTrace() }
            }
        } catch (e: Exception) { e.printStackTrace() }
    }

    private fun toMediaUri(path: String): android.net.Uri =
        if (path.startsWith("content://") || path.startsWith("file://")) android.net.Uri.parse(path)
        else android.net.Uri.fromFile(File(path))

    /**
     * Convierte un Song.artUri (path local tipo /data/.../cacheDir/art/art_XYZ.jpg)
     * en un content:// URI via FileProvider, para que DefaultMediaNotificationProvider
     * pueda cargarlo como artworkUri en la MediaStyle notification.
     *
     * Devuelve null si:
     *  - artUri es null/blank
     *  - el archivo no existe (portada perdida tras reinstalar)
     *  - FileProvider falla
     */
    private fun toArtworkUri(song: Song): android.net.Uri? {
        val artPath = song.artUri?.takeIf { it.isNotBlank() } ?: return null
        val artFile = File(artPath)
        if (!artFile.exists()) return null
        return try {
            androidx.core.content.FileProvider.getUriForFile(
                appCtx!!,
                "${appCtx!!.packageName}.artwork",
                artFile
            )
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun setQueue(songs: List<Song>, startIndex: Int) {
        try {
            val p = ensureConnected() ?: return
            if (songs.isEmpty()) return
            val items = songs.map { s ->
                val metadataBuilder = MediaMetadata.Builder()
                    .setTitle(s.displayTitle)
                    .setArtist(s.displayArtist)
                    .setAlbumTitle(s.displayAlbum)
                // #2 MediaStyle notification artwork: artworkUri via FileProvider
                // para que la tarjeta en quick-settings dropdown y el Dynamic Island
                // muestren la portada del album (como Spotify).
                toArtworkUri(s)?.let { metadataBuilder.setArtworkUri(it) }
                MediaItem.Builder()
                    .setUri(toMediaUri(s.filePath))
                    .setMediaId(s.id.toString())
                    .setMediaMetadata(metadataBuilder.build())
                    .build()
            }
            lastRecordedWindow = -1
            val safeIndex = startIndex.coerceIn(0, items.size - 1)
            p.setMediaItems(items, safeIndex, 0L)
            _state.value = _state.value.copy(playlist = songs)
            p.prepare()
            p.play()
            refreshState()
        } catch (e: Exception) {
            e.printStackTrace()
            try { player?.release() } catch (_: Exception) {}
            player = null
        }
    }

    fun play() {
        try {
            val p = ensureConnected() ?: return
            if (p.playbackState == Player.STATE_ENDED || p.playbackState == Player.STATE_IDLE) {
                if (p.mediaItemCount > 0) {
                    p.prepare()
                    if (p.playbackState == Player.STATE_ENDED) p.seekTo(p.currentMediaItemIndex, 0L)
                }
            }
            p.play()
            refreshState()
        } catch (e: Exception) {
            e.printStackTrace()
            try { player?.release() } catch (_: Exception) {}
            player = null
        }
    }

    fun pause() {
        try {
            val p = ensureConnected() ?: return
            p.pause()
            refreshState()
        } catch (e: Exception) {
            e.printStackTrace()
            try { player?.release() } catch (_: Exception) {}
            player = null
        }
    }

    fun stop() {
        try {
            val p = ensureConnected() ?: return
            p.stop()
            p.clearMediaItems()
            _state.value = PlaybackState()
        } catch (e: Exception) {
            e.printStackTrace()
            try { player?.release() } catch (_: Exception) {}
            player = null
            _state.value = PlaybackState()
        }
    }

    fun skipToNext() {
        try {
            val p = ensureConnected() ?: return
            if (p.hasNextMediaItem()) {
                p.seekToNextMediaItem()
            } else if (p.playbackState == Player.STATE_ENDED && p.mediaItemCount > 0) {
                p.seekTo(0, 0L)
                p.prepare()
            }
            refreshState()
        } catch (e: Exception) {
            e.printStackTrace()
            try { player?.release() } catch (_: Exception) {}
            player = null
        }
    }

    fun skipToPrevious() {
        try {
            val p = ensureConnected() ?: return
            if (p.hasPreviousMediaItem()) {
                p.seekToPreviousMediaItem()
            } else if (p.mediaItemCount > 0) {
                p.seekTo(p.mediaItemCount - 1, 0L)
            }
            refreshState()
        } catch (e: Exception) {
            e.printStackTrace()
            try { player?.release() } catch (_: Exception) {}
            player = null
        }
    }

    fun seekToWindow(index: Int) {
        try {
            val p = ensureConnected() ?: return
            p.seekTo(index, 0L)
            refreshState()
        } catch (e: Exception) {
            e.printStackTrace()
            try { player?.release() } catch (_: Exception) {}
            player = null
        }
    }

    fun seekTo(positionMs: Long) {
        try {
            val p = ensureConnected() ?: return
            p.seekTo(positionMs.coerceAtLeast(0L))
            refreshState()
        } catch (e: Exception) {
            e.printStackTrace()
            try { player?.release() } catch (_: Exception) {}
            player = null
        }
    }

    fun setShuffle(enabled: Boolean) {
        try {
            val p = ensureConnected() ?: return
            p.shuffleModeEnabled = enabled
            refreshState()
        } catch (e: Exception) {
            e.printStackTrace()
            try { player?.release() } catch (_: Exception) {}
            player = null
        }
    }

    fun setRepeatOne(enabled: Boolean) {
        try {
            val p = ensureConnected() ?: return
            p.repeatMode = if (enabled) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
            refreshState()
        } catch (e: Exception) {
            e.printStackTrace()
            try { player?.release() } catch (_: Exception) {}
            player = null
        }
    }

    fun setVolumeBoost(gain: Float) {
        try {
            volumeBoost = gain
            val p = ensureConnected() ?: return
            p.volume = gain.coerceIn(0f, 1f)
        } catch (e: Exception) {
            e.printStackTrace()
            try { player?.release() } catch (_: Exception) {}
            player = null
        }
    }

    fun getAudioSessionId(): Int {
        // CRITICAL (Bug EQ no funciona): devolver primero el sessionId que seteamos
        // durante connect(). ExoPlayer.audioSessionId puede reportar 0 antes del primer
        // playback aunque hayamos llamado setAudioSessionId().
        val committed = committedAudioSessionId
        if (committed != 0) return committed
        return try {
            val p = player
            if (p is ExoPlayer) p.audioSessionId else 0
        } catch (e: Exception) { e.printStackTrace(); 0 }
    }

    suspend fun currentPosition(): Long = withContext(Dispatchers.Main) {
        try { player?.currentPosition ?: 0L } catch (e: Exception) { 0L }
    }
}
