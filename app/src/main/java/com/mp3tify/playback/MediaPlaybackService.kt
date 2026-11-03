package com.mp3tify.playback

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.os.Build
import androidx.media3.common.Player
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

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
            val player = PlaybackController.playerOrNull()
            if (player != null) {
                mediaSession = MediaSession.Builder(this, player).build()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
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
                mediaSession = MediaSession.Builder(this, fresh).build()
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
