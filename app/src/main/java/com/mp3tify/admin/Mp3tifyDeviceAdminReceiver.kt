package com.mp3tify.admin

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent

/**
 * DeviceAdminReceiver mínimo para permitir que el Sleep Timer apague/bloquee
 * la pantalla al instante mediante DevicePolicyManager.lockNow().
 */
class Mp3tifyDeviceAdminReceiver : DeviceAdminReceiver() {
    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
    }
}
