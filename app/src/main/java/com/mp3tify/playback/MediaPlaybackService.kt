package com.mp3tify.playback

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import androidx.media3.common.Player
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.mp3tify.MainActivity

/**
 * MediaSessionService — exposes the PlaybackController's ExoPlayer to the system
 * (notification, Bluetooth/steering-wheel buttons, Android Auto, Assistant).
 *
 * CRITICAL FIX (botones volante mueren tras inactividad): onGetSession ahora
 * verifies that the shared player is still alive on every controller connection.
 * If the player was released by the system after a long idle period, we discard
 * the stale MediaSession, ask PlaybackController to reconnect (creates a fresh
 * ExoPlayer), and build a new MediaSession wrapping the fresh player. Before
 * this fix, the MediaSession kept pointing at a released player and the car's
 * SKIP/PREV buttons were no-ops until the user opened the app and forced a
 * reconnect from the UI.
 */
class MediaPlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        try {
            createChannel()
            PlaybackController.connect(applicationContext)
            // #3 Optimización segundo plano: acquire PARTIAL_WAKE_LOCK desde el service
            // para que el CPU no se duerma durante playback en background, incluso si
            // la Activity no está visible. Esto previene que el ExoPlayer se congele
            // tras un rato de uso en background.
            WakeLockManager.acquirePartialWakeLock(applicationContext)
            val player = PlaybackController.playerOrNull()
            if (player != null) {
                // #2 MediaStyle notification: set session activity so tapping the
                // notification card (in quick settings dropdown / Dynamic Island)
                // opens the app's MainActivity. Without this, tapping the notification
                // does nothing or opens the system's media control center.
                val sessionIntent = Intent(this, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
                val pendingIntent = PendingIntent.getActivity(
                    this,
                    0,
                    sessionIntent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                mediaSession = MediaSession.Builder(this, player)
                    .setSessionActivity(pendingIntent)
                    .build()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // CRITICAL (#3 playback resilience): START_STICKY para que el system reintente
        // el service si lo mata por memory pressure. Sin esto, cuando el system mata
        // el MediaPlaybackService (tras un rato de inactividad), el service NO se
        // recrea y el MediaSession desaparece del MediaSessionManager → no hay
        // notification, no hay Dynamic Island, y la próxima vez que el usuario abre
        // Wave y le da play, setQueue llama a un player que el system ya liberó.
        // Con START_STICKY, Android recrea el service (con intent=null) y
        // onCreate vuelve a conectar el PlaybackController + crear MediaSession.
        super.onStartCommand(intent, flags, startId)
        // CRITICAL (#3): si el service ya estaba corriendo (onCreate no se llama de
        // nuevo) pero PlaybackController reconectó con un player NUEVO (porque el
        // viejo fue liberado), el MediaSession actual apunta al player muerto.
        // Detectar esto y reconstruir el session sobre el player vivo.
        try {
            val session = mediaSession
            val sessionPlayer = session?.player
            val currentControllerPlayer = PlaybackController.playerOrNull()
            if (sessionPlayer != null && currentControllerPlayer != null && sessionPlayer !== currentControllerPlayer) {
                android.util.Log.w("MediaPlaybackService", "onStartCommand: MediaSession player != PlaybackController player, rebuilding session")
                try { session.release() } catch (_: Exception) {}
                mediaSession = null
                val sessionIntent = Intent(this, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
                val pendingIntent = PendingIntent.getActivity(
                    this, 0, sessionIntent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                mediaSession = MediaSession.Builder(this, currentControllerPlayer)
                    .setSessionActivity(pendingIntent)
                    .build()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return START_STICKY
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        // CRITICAL FIX (botones volante mueren): on every controller connect
        // (car, notification, Assistant), probe the player. If it's released,
        // rebuild the session so the buttons drive a live player.
        val session = mediaSession
        val player = session?.player
        if (player != null) {
            try {
                @Suppress("UNUSED_VARIABLE")
                val state = player.playbackState
                return session  // alive
            } catch (e: Exception) {
                // Player released — discard the stale session and rebuild.
                try { session.release() } catch (_: Exception) {}
                mediaSession = null
            }
        }
        // Reconnect and rebuild.
        try {
            PlaybackController.connect(applicationContext)
            val fresh = PlaybackController.playerOrNull()
            if (fresh != null) {
                // Re-build session with session activity + notification provider (matching onCreate).
                val sessionIntent = Intent(this, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
                val pendingIntent = PendingIntent.getActivity(
                    this,
                    0,
                    sessionIntent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                mediaSession = MediaSession.Builder(this, fresh)
                    .setSessionActivity(pendingIntent)
                    .build()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return mediaSession
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        try { PlaybackController.stop() } catch (e: Exception) { e.printStackTrace() }
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        try { mediaSession?.release() } catch (e: Exception) { e.printStackTrace() }
        mediaSession = null
        // #3 Liberar el wakelock al destruir el service para no leakar recursos.
        try { WakeLockManager.releasePartialWakeLock() } catch (e: Exception) { e.printStackTrace() }
        super.onDestroy()
    }

    private fun createChannel() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    "mp3tify_playback",
                    getString(com.mp3tify.R.string.notification_channel_name),
                    NotificationManager.IMPORTANCE_LOW
                )
                channel.description = getString(com.mp3tify.R.string.notification_channel_desc)
                val nm = getSystemService(NotificationManager::class.java)
                nm.createNotificationChannel(channel)
            }
        } catch (e: Exception) { e.printStackTrace() }
    }
}
