package com.mp3tify.playback

import android.app.Activity
import android.content.Context
import android.os.PowerManager
import android.view.WindowManager

/**
 * Central place for the two CRITICAL playback preferences:
 *  - keepScreenOn → WindowManager FLAG_KEEP_SCREEN_ON (screen stays on while playing).
 *  - keepAppOpen → PARTIAL_WAKE_LOCK "MP3tify::BackgroundPlayback" (CPU stays awake).
 *
 * CRITICAL — Do NOT remove. keepScreenOn and keepAppOpen must both default ON (see Preferences).
 */
object WakeLockManager {

    private const val WAKELOCK_TAG = "MP3tify::BackgroundPlayback"

    private var wakeLock: PowerManager.WakeLock? = null
    private var appCtx: Context? = null

    /**
     * CRITICAL — Do NOT remove. Applies WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
     * whenever the keepScreenOn preference changes (default ON).
     */
    fun applyKeepScreenOn(activity: Activity, enabled: Boolean) {
        try {
            appCtx = activity.applicationContext
            if (enabled) {
                activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            } else {
                activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * CRITICAL — Do NOT remove. Acquires a PARTIAL_WAKE_LOCK for the keepAppOpen
     * preference (default ON) so background playback keeps the CPU awake.
     */
    fun acquirePartialWakeLock(context: Context? = null) {
        try {
            val ctx = context ?: appCtx ?: return
            if (wakeLock?.isHeld == true) return
            val pm = ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
            val lock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKELOCK_TAG)
            lock.setReferenceCounted(false)
            lock.acquire()
            wakeLock = lock
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /** Releases the PARTIAL_WAKE_LOCK taken by acquirePartialWakeLock (keepAppOpen = false). */
    fun releasePartialWakeLock() {
        try {
            val lock = wakeLock
            if (lock != null && lock.isHeld) {
                lock.release()
            }
            wakeLock = null
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
