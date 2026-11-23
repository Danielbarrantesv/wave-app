package com.mp3tify.playback

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.view.WindowManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Central place for the two CRITICAL playback preferences:
 *  - keepScreenOn → WindowManager FLAG_KEEP_SCREEN_ON (screen stays on while playing).
 *  - keepAppOpen → PARTIAL_WAKE_LOCK "MP3tify::BackgroundPlayback" (CPU stays awake).
 */
object WakeLockManager {

    private const val WAKELOCK_TAG = "MP3tify::BackgroundPlayback"

    private var wakeLock: PowerManager.WakeLock? = null
    private var appCtx: Context? = null

    private val _deviceAdminActive = MutableStateFlow(false)
    val deviceAdminActive: StateFlow<Boolean> = _deviceAdminActive.asStateFlow()

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

    fun isDeviceAdminActive(context: Context): Boolean {
        return try {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE)
                    as? DevicePolicyManager ?: return false
            val admin = ComponentName(
                context.packageName,
                "com.mp3tify.admin.Mp3tifyDeviceAdminReceiver"
            )
            dpm.isAdminActive(admin)
        } catch (_: Exception) {
            false
        }
    }

    fun refreshDeviceAdminStatus(context: Context) {
        _deviceAdminActive.value = isDeviceAdminActive(context.applicationContext)
    }

    /**
     * Apaga la pantalla al expirar el sleep timer:
     * 1. Limpia FLAG_KEEP_SCREEN_ON y baja brillo de ventana al mínimo.
     * 2. Libera PARTIAL_WAKE_LOCK.
     * 3. Si Device Admin está activo, ejecuta lockNow() para apagar la pantalla al instante.
     *    Si no está activo, envía la app a segundo plano para que el sistema apague la pantalla.
     */
    fun turnOffScreen(activity: Activity?, appContext: Context) {
        try {
            activity?.let { act ->
                act.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                val lp = act.window.attributes
                lp.screenBrightness = 0.01f
                act.window.attributes = lp
            }
            releasePartialWakeLock()

            if (isDeviceAdminActive(appContext)) {
                lockScreenWithDeviceAdmin(appContext)
            } else {
                activity?.moveTaskToBack(true)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun lockScreenWithDeviceAdmin(context: Context) {
        try {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE)
                    as? DevicePolicyManager ?: return
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

    fun requestDeviceAdmin(activity: Activity) {
        try {
            val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
            val admin = ComponentName(
                activity.packageName,
                "com.mp3tify.admin.Mp3tifyDeviceAdminReceiver"
            )
            intent.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin)
            intent.putExtra(
                DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                "Permite que Wave apague la pantalla automáticamente cuando termine el temporizador de sueño."
            )
            activity.startActivity(intent)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
