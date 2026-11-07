package com.mp3tify.playback

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
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

    /**
     * CRITICAL FIX (loop infinito tras restart): cuando onPlayerError recibe un
     * error permanente (IO/permission/decoding), seteamos esta flag. playInternal
     * la consulta antes de reintentar — si es true, salta a la siguiente canción o
     * detiene en lugar de re-apply del queue (que solo reproduciría el mismo error).
     * Se resetea al cambiar de canción exitosamente (onMediaItemTransition).
     */
    @Volatile
    private var lastErrorWasPermanent: Boolean = false

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
                    // CRITICAL FIX (loop infinito): resetear la flag de error permanente
                    // al cambiar de canción — la nueva canción puede ser accesible.
                    if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO ||
                        reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK) {
                        lastErrorWasPermanent = false
                    }
                }
                override fun onPlaybackStateChanged(playbackState: Int) { refreshState() }
                override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
                    _state.value = _state.value.copy(shuffle = shuffleModeEnabled)
                }
                override fun onRepeatModeChanged(repeatMode: Int) {
                    _state.value = _state.value.copy(repeatOne = repeatMode == Player.REPEAT_MODE_ONE)
                }
                override fun onPlayerError(error: PlaybackException) {
                    // CRITICAL FIX (loop infinito tras restart): clasificar el error
                    // por errorCode. Los IO/permission/decoding son permanentes — no
                    // van a desaparecer reintenteando. Marcar la flag para que
                    // playInternal no reintente con setQueue (eso solo reproduciría el
                    // mismo SecurityException infinitamente, como se ve en el log
                    // "player empty but state has 2 songs → re-apply" disparándose 4
                    // veces en 2 segundos).
                    lastErrorWasPermanent = when (error.errorCode) {
                        PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
                        PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
                        // CRITICAL FIX (build error Media3 1.4.1): la constante
                        // ERROR_CODE_IO_PERMISSION_DENIED fue introducida en Media3
                        // 1.5.0; en 1.4.1 no existe y el build falla con
                        // 'Unresolved reference'. La quitamos de la lista de
                        // permanentes — los SecurityException reales de SAF
                        // típicamente se mapean a ERROR_CODE_IO_UNSPECIFIED
                        // en Media3 1.4.x, así que siguen cubiertos.
                        PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE,
                        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
                        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
                        PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
                        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
                        PlaybackException.ERROR_CODE_DECODING_FAILED -> true
                        else -> false
                    }
                    // CRITICAL FIX (build error Media3 1.4.1): errorCodeNameString
                    // fue introducido en Media3 1.5.0; en 1.4.1 no existe. Usamos
                    // error.errorCode (Int) que siempre existió — el log muestra
                    // el número (-1007, -1003, etc.) en lugar del nombre legible,
                    // pero es suficiente para diagnóstico.
                    Log.e("PlaybackController", "onPlayerError: code=${error.errorCode} permanent=$lastErrorWasPermanent message=${error.message}", error)
                    // CRITICAL FIX (play button deja de funcionar): antes solo haciamos
                    // printStackTrace + refreshState. El player quedaba en estado zombie
                    // (STATE_IDLE con error) y play() no podia recuperarlo porque
                    // ensureConnected() veia playbackState sin lanzar excepcion.
                    // Ahora: liberar el player y setear a null para que el proximo
                    // play() llame a ensureConnected() → connect() → crea player nuevo.
                    try { player?.release() } catch (_: Exception) {}
                    player = null
                    committedAudioSessionId = 0
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
        // CRITICAL FIX (app se congela con bibliotecas grandes):
        // Antes construiamos los MediaItems en el main thread, incluyendo
        // toArtworkUri() que hace File.exists() por cada cancion (disk I/O).
        // Para 668 canciones = 668 File.exists() en main thread = ANR.
        // Ahora: construir MediaItems en IO thread, aplicar en Main thread.
        _state.value = _state.value.copy(playlist = songs)
        val safeIndex = startIndex.coerceIn(0, songs.size - 1)
        lastRecordedWindow = -1
        // Guardar cola para restaurar despues de process kill (#2 queue persistence)
        saveQueueToPrefs(songs, safeIndex)

        ioScope.launch {
            try {
                val items = songs.map { s ->
                    val metadataBuilder = MediaMetadata.Builder()
                        .setTitle(s.displayTitle)
                        .setArtist(s.displayArtist)
                        .setAlbumTitle(s.displayAlbum)
                    // toArtworkUri hace File.exists() — disk I/O pesado con muchas canciones.
                    // Ahora corre en IO thread.
                    toArtworkUri(s)?.let { metadataBuilder.setArtworkUri(it) }
                    MediaItem.Builder()
                        .setUri(toMediaUri(s.filePath))
                        .setMediaId(s.id.toString())
                        .setMediaMetadata(metadataBuilder.build())
                        .build()
                }
                // Volver al main thread para interactuar con ExoPlayer (requiere main thread).
                withContext(Dispatchers.Main) {
                    val p = ensureConnected() ?: return@withContext
                    p.setMediaItems(items, safeIndex, 0L)
                    p.prepare()
                    p.play()
                    refreshState()
                }
            } catch (e: Exception) {
                Log.e("PlaybackController", "setQueue: error building/setting items", e)
                try { player?.release() } catch (_: Exception) {}
                player = null
            }
        }
    }

    /**
     * Persiste la cola actual (song IDs + index) en SharedPreferences para poder
     * restaurarla despues de que el process sea killado (call, memory pressure).
     */
    private fun saveQueueToPrefs(songs: List<Song>, startIndex: Int) {
        try {
            val ctx = appCtx ?: return
            val prefs = ctx.getSharedPreferences("mp3tify_playback", android.content.Context.MODE_PRIVATE)
            val ids = songs.joinToString(",") { it.id.toString() }
            prefs.edit()
                .putString("queue_song_ids", ids)
                .putInt("queue_index", startIndex)
                .putLong("queue_saved_at", System.currentTimeMillis())
                .apply()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Restaura la cola guardada desde SharedPreferences. Llamado desde
     * MainActivity.onCreate para que el usuario pueda resumir la ultima cola
     * despues de un process kill (call, memory pressure, etc).
     *
     * Requiere las canciones de la DB — el caller debe pasar el Repository.
     *
     * CRITICAL FIX (SecurityException tras restart): antes de pasar las canciones
     * al ExoPlayer, validamos que cada URI sea accesible. Tras un process kill,
     * los URIs content:// del SAF pueden perder el persistable permission (raro
     * pero posible en Android 14 — el sistema poda persistable permissions si el
     * granting package se actualiza, o si la app fue reinstalada sin restaurar
     * los tree URIs via BackupUriManager.restoreTreeUris). Pasar un URI muerto
     * al ExoPlayer dispara onPlayerError con ERROR_CODE_IO_UNSPECIFIED, que a su
     * vez dispara el loop de retry en playInternal. Filtramos aquí los caídos.
     */
    fun restoreSavedQueue(repo: com.mp3tify.data.repository.Repository) {
        try {
            val ctx = appCtx ?: return
            val prefs = ctx.getSharedPreferences("mp3tify_playback", android.content.Context.MODE_PRIVATE)
            val idsStr = prefs.getString("queue_song_ids", "") ?: return
            if (idsStr.isBlank()) return
            val savedIndex = prefs.getInt("queue_index", 0)
            val ids = idsStr.split(",").mapNotNull { it.toLongOrNull() }
            if (ids.isEmpty()) return
            ioScope.launch {
                try {
                    val allSongs = repo.allSongs()
                    val songs = ids.mapNotNull { id -> allSongs.firstOrNull { it.id == id } }
                    if (songs.isEmpty()) {
                        // Las canciones ya no existen en la DB — limpiar prefs.
                        prefs.edit().remove("queue_song_ids").remove("queue_index").apply()
                        return@launch
                    }
                    // CRITICAL FIX: filtrar canciones cuyo filePath ya no es accesible.
                    // isUriAccessible abre un InputStream y lee 1 byte — fuerza el
                    // check real de permission contra el ContentProvider.
                    val accessible = songs.filter { isUriAccessible(ctx, it.filePath) }
                    val dropped = songs.size - accessible.size
                    if (dropped > 0) {
                        Log.w("PlaybackController", "restoreSavedQueue: dropped $dropped inaccessible song(s) out of ${songs.size}")
                    }
                    if (accessible.isEmpty()) {
                        // CRITICAL FIX (v2.5.14): antes limpiabamos los prefs si todas las
                        // canciones eran inaccesibles. Eso hacia que al reabrir la app, la
                        // cola estuviera vacía y el play no respondiera — el usuario perdía
                        // su cola sin poder reintentar.
                        //
                        // Ahora NO limpiamos los prefs. Solo logeamos warning. El usuario
                        // puede re-seleccionar la carpeta desde Settings (que re-toma los
                        // tree URIs) y la próxima vez que reabra Wave, isUriAccessible
                        // debería devolver true para esos URIs.
                        Log.w("PlaybackController", "restoreSavedQueue: all ${songs.size} songs inaccessible — keeping prefs for retry (user should re-select folder from Settings)")
                        _state.value = _state.value.copy(playlist = songs, currentWindow = savedIndex.coerceIn(0, songs.size - 1))
                        return@launch
                    }
                    // Ajustar el savedIndex si las canciones anteriores al índice fueron caídas.
                    val droppedBeforeIdx = songs.subList(0, savedIndex.coerceIn(0, songs.size)).count { !isUriAccessible(ctx, it.filePath) }
                    val adjustedIndex = (savedIndex - droppedBeforeIdx).coerceIn(0, accessible.size - 1)
                    withContext(Dispatchers.Main) {
                        setQueue(accessible, adjustedIndex)
                        // Pausar en vez de reproducir automaticamente (el usuario
                        // puede reanudar con el boton de play cuando quiera).
                        try { player?.pause() } catch (_: Exception) {}
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * CRITICAL FIX (v2.5.14 playback): valida si un filePath es accesible.
     *
     * CAMBIO v2.5.14: antes, cualquier excepción (incluidas IOException
     * transitorias o ContentProvider timeouts) hacía que devolviera false y
     * restoreSavedQueue borrara la cola entera. Eso causaba que al reabrir
     * la app tras cerrarla, el play dejara de funcionar — el usuario veía
     * una playlist vacía sin razón.
     *
     * Ahora solo devolvemos false si estamos SEGUROS de que el URI está
     * muerto: SecurityException (permiso perdido) o FileNotFoundException
     * (archivo borrado). Otras excepciones (IOException transitorio, etc.)
     * devuelven true — dejamos que ExoPlayer intente reproducir; si falla,
     * onPlayerError lo clasifica y playInternal hace skipToNext o stop.
     */
    private fun isUriAccessible(context: Context, path: String): Boolean {
        if (path.isBlank()) return false
        return try {
            if (path.startsWith("content://") || path.startsWith("file://")) {
                val uri = android.net.Uri.parse(path)
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    stream.read()
                    true
                } ?: false
            } else {
                val f = File(path)
                f.exists() && f.canRead()
            }
        } catch (e: SecurityException) {
            // Permiso perdido — URI muerto. Solo este caso debe devolver false.
            Log.w("PlaybackController", "isUriAccessible: SecurityException for $path (permission lost)")
            false
        } catch (e: java.io.FileNotFoundException) {
            // Archivo borrado — URI muerto.
            Log.w("PlaybackController", "isUriAccessible: FileNotFoundException for $path")
            false
        } catch (e: Exception) {
            // IOException transitorio, timeout del ContentProvider, etc. — NO
            // descartar el URI. Dejar que ExoPlayer intente reproducirlo; si
            // realmente está roto, onPlayerError clasificará el error y
            // playInternal hará skipToNext o stop. Esto evita que se borre la
            // cola entera por un false negative.
            Log.w("PlaybackController", "isUriAccessible: ${e.javaClass.simpleName} for $path (will let ExoPlayer try): ${e.message}")
            true
        }
    }

    fun play() {
        playInternal(retryOnFailure = true)
    }

    /**
     * Logica interna de play(). Si retryOnFailure es true y el primer intento falla
     * (player zombie, STATE_IDLE con media items, o excepcion), libera el player,
     * reconecta, restaura la cola guardada, y reintenta. Esto previene el bug
     * "play button deja de funcionar" donde el usuario tenia que reinstalar la app.
     *
     * CRITICAL FIX (loop infinito tras restart): si onPlayerError marcó
     * lastErrorWasPermanent=true (SecurityException/IO/decoding), NO reintenteamos —
     * el archivo está inaccesible y reintentar solo dispara el mismo error. En su
     * lugar, avanzamos a la siguiente canción o detenemos. Esto rompe el loop
     * "player empty → re-apply → onPlayerError → player empty → ..." visible en el
     * log de v2.5.11 (4 intentos en ~2 segundos).
     */
    private fun playInternal(retryOnFailure: Boolean) {
        // CRITICAL FIX (loop infinito): si el último error fue permanente, saltar
        // a la siguiente canción accesible o detener.
        if (lastErrorWasPermanent) {
            Log.w("PlaybackController", "play: skipping — last error was permanent (errorCode set flag)")
            val pl = _state.value.playlist
            val idx = _state.value.currentWindow
            if (pl.isNotEmpty() && idx >= 0 && idx < pl.size) {
                Log.w("PlaybackController", "play: song at index $idx is inaccessible (${pl[idx].fileName}), skipping to next")
                if (_state.value.hasNext) {
                    // Reset flag para la siguiente canción y avanzar.
                    lastErrorWasPermanent = false
                    skipToNext()
                } else {
                    // No hay siguiente — detener y limpiar la cola fallida.
                    stop()
                }
            }
            return
        }
        try {
            val p = ensureConnected() ?: run {
                Log.w("PlaybackController", "play: ensureConnected returned null (appCtx=${appCtx != null}, player=${player != null})")
                return
            }
            if (p.playbackState == Player.STATE_ENDED || p.playbackState == Player.STATE_IDLE) {
                if (p.mediaItemCount > 0) {
                    p.prepare()
                    if (p.playbackState == Player.STATE_ENDED) p.seekTo(p.currentMediaItemIndex, 0L)
                }
            }
            p.play()
            refreshState()
            // CRITICAL FIX: si despues de play() el player sigue sin reproducir y esta
            // en STATE_IDLE con media items, esta zombie. Liberar, reconectar, restaurar
            // la cola, y reintentar — PERO solo si el último error NO fue permanente
            // (si fue permanente, ya salimos arriba con skipToNext/stop).
            if (!p.isPlaying && p.playbackState == Player.STATE_IDLE && p.mediaItemCount > 0) {
                Log.w("PlaybackController", "play: player stuck in STATE_IDLE after play(), reconnect + restore queue")
                val savedQueue = _state.value.playlist
                val savedIndex = _state.value.currentWindow
                try { p.release() } catch (_: Exception) {}
                player = null
                committedAudioSessionId = 0
                if (retryOnFailure && savedQueue.isNotEmpty() && !lastErrorWasPermanent) {
                    // Reconnect creates a fresh player. setQueue restores the media
                    // items and calls play() internally.
                    Log.i("PlaybackController", "play: retrying with ${savedQueue.size} songs, index=$savedIndex")
                    setQueue(savedQueue, savedIndex.coerceAtLeast(0))
                }
            }
        } catch (e: Exception) {
            Log.e("PlaybackController", "play: exception, releasing player", e)
            val savedQueue = _state.value.playlist
            val savedIndex = _state.value.currentWindow
            try { player?.release() } catch (_: Exception) {}
            player = null
            if (retryOnFailure && savedQueue.isNotEmpty() && !lastErrorWasPermanent) {
                Log.i("PlaybackController", "play: retrying after exception with ${savedQueue.size} songs")
                setQueue(savedQueue, savedIndex.coerceAtLeast(0))
            }
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
