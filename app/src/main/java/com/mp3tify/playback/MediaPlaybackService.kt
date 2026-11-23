package com.mp3tify.playback

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import androidx.annotation.OptIn
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.mp3tify.MainActivity
import com.mp3tify.R

@OptIn(UnstableApi::class)
class MediaPlaybackService : MediaSessionService() {

    companion object {
        const val CHANNEL_ID = "mp3tify_playback"
        const val NOTIFICATION_ID = 1001
        const val ACTION_REFRESH_NOTIFICATION = "com.mp3tify.ACTION_REFRESH_NOTIFICATION"
    }

    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        try {
            createChannel()
            val provider = DefaultMediaNotificationProvider.Builder(this)
                .setChannelId(CHANNEL_ID)
                .setChannelName(R.string.notification_channel_name)
                .setNotificationId(NOTIFICATION_ID)
                .build().apply { setSmallIcon(R.drawable.ic_wave_notification) }
            setMediaNotificationProvider(provider)

            PlaybackController.connect(applicationContext)
            WakeLockManager.acquirePartialWakeLock(applicationContext)
            PlaybackController.playerOrNull()?.let { buildOrReplaceSession(it) }
        } catch (e: Exception) { e.printStackTrace() }
    }

    private fun buildOrReplaceSession(player: Player): MediaSession {
        mediaSession?.let { old ->
            try { removeSession(old) } catch (_: Exception) {}
            try { old.release() } catch (_: Exception) {}
            mediaSession = null
        }
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return MediaSession.Builder(this, player).setSessionActivity(pi).build().also { mediaSession = it }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        try {
            val cur = PlaybackController.playerOrNull()
            val s = if (cur != null && (mediaSession == null || mediaSession?.player !== cur)) {
                buildOrReplaceSession(cur)
            } else mediaSession
            if (s != null && s.player.mediaItemCount > 0 &&
                (s.player.playWhenReady || intent?.action == ACTION_REFRESH_NOTIFICATION)
            ) {
                onUpdateNotification(s, s.player.playWhenReady)
            }
        } catch (e: Exception) { e.printStackTrace() }
        return START_STICKY
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        val s = mediaSession
        if (s?.player != null) {
            try { @Suppress("UNUSED_VARIABLE") val st = s.player.playbackState; return s }
            catch (_: Exception) {
                try { removeSession(s) } catch (_: Exception) {}
                try { s.release() } catch (_: Exception) {}
                mediaSession = null
            }
        }
        try {
            PlaybackController.connect(applicationContext)
            PlaybackController.playerOrNull()?.let { return buildOrReplaceSession(it) }
        } catch (e: Exception) { e.printStackTrace() }
        return mediaSession
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        try { PlaybackController.playerOrNull()?.let { it.pause(); it.clearMediaItems() } }
        catch (e: Exception) { e.printStackTrace() }
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        mediaSession?.let {
            try { removeSession(it) } catch (_: Exception) {}
            try { it.release() } catch (e: Exception) { e.printStackTrace() }
        }
        mediaSession = null
        try { WakeLockManager.releasePartialWakeLock() } catch (e: Exception) { e.printStackTrace() }
        super.onDestroy()
    }

    private fun createChannel() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val ch = NotificationChannel(
                    CHANNEL_ID, getString(R.string.notification_channel_name),
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = getString(R.string.notification_channel_desc)
                    setShowBadge(false); enableVibration(false); setSound(null, null)
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                }
                getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
            }
        } catch (e: Exception) { e.printStackTrace() }
    }
}
