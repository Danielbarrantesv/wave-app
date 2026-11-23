package com.mp3tify.ui.components

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.mp3tify.playback.SleepTimerManager
import com.mp3tify.playback.WakeLockManager
import com.mp3tify.ui.t

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Composable
fun SleepTimerDialog(
    activeMinutes: Int,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val activity = context.findActivity()
    val adminActive by WakeLockManager.deviceAdminActive.collectAsState()

    LaunchedEffect(Unit) {
        WakeLockManager.refreshDeviceAdminStatus(context)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Temporizador de sueño") },
        text = {
            Column {
                if (!adminActive && activity != null) {
                    OutlinedButton(
                        onClick = { WakeLockManager.requestDeviceAdmin(activity) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Activar apagado real de pantalla")
                    }
                    Spacer(Modifier.height(8.dp))
                }
                listOf(15, 30, 45, 60, 90).forEach { minutes ->
                    TextButton(
                        onClick = {
                            SleepTimerManager.start(context, minutes)
                            onDismiss()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            if (activeMinutes == minutes) "$minutes minutos (activo)" else "$minutes minutos",
                            color = if (activeMinutes == minutes) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
                if (activeMinutes > 0) {
                    Spacer(Modifier.height(8.dp))
                    TextButton(
                        onClick = {
                            SleepTimerManager.cancel()
                            onDismiss()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Cancelar temporizador") }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(t("close")) } }
    )
}
