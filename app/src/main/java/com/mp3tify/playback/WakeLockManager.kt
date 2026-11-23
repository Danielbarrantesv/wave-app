package com.mp3tify.playback

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
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

    /**
     * CRITICAL (v2.5.39 sleep timer): apaga la pantalla al expirar el temporizador.
     *
     * 1. Limpia FLAG_KEEP_SCREEN_ON (la pantalla puede apagarse según el timeout del sistema).
     * 2. Libera el PARTIAL_WAKE_LOCK (CPU puede suspenderse, música ya está pausada).
     * 3. Finaliza la Activity actual para que el sistema lleve la app a background
     *    y la pantalla se apague naturalmente según el Display timeout del usuario.
     *
     * NO bloquea el dispositivo (lock screen). Para bloqueo real ver [lockScreenWithDeviceAdmin].
     */
    fun turnOffScreen(activity: Activity) {
        try {
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            releasePartialWakeLock()
            activity.finish()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * CRITICAL (v2.5.39 sleep timer, OPCIONAL): bloquea el dispositivo usando DevicePolicyManager.
     *
     * Requiere que el usuario haya activado Device Admin para MP3tify (flujo
     * [requestDeviceAdmin]). Si NO es admin activo, NO bloquea — usar [turnOffScreen] como fallback.
     */
    fun lockScreenWithDeviceAdmin(context: Context) {
        try {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE)
                    as? android.app.admin.DevicePolicyManager ?: return
            val admin = ComponentName(
                context.packageName,
                "com.mp3tify.admin.Mp3tifyDeviceAdminReceiver"
            )
            if (dpm.isAdminActive(admin)) {
                dpm.lockNow()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * CRITICAL (v2.5.39 sleep timer, OPCIONAL): lanza el flujo del sistema para que el
     * usuario conceda Device Admin a MP3tify. Solo necesario si se quiere bloqueo real.
     */
    fun requestDeviceAdmin(activity: Activity) {
        try {
            val intent = Intent(android.app.admin.DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
            val admin = ComponentName(
                activity.packageName,
                "com.mp3tify.admin.Mp3tifyDeviceAdminReceiver"
            )
            intent.putExtra(
                android.app.admin.DevicePolicyManager.EXTRA_DEVICE_ADMIN,
                admin
            )
            activity.startActivity(intent)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
