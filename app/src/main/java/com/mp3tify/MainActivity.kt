package com.mp3tify

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import com.mp3tify.data.repository.Repository
import com.mp3tify.playback.PlaybackController
import com.mp3tify.playback.WakeLockManager
import com.mp3tify.ui.AppNav
import com.mp3tify.ui.ProvideAppLanguage
import com.mp3tify.ui.t
import com.mp3tify.ui.theme.AccentColor
import com.mp3tify.ui.theme.Mp3tifyTheme
import com.mp3tify.util.Preferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try { PlaybackController.connect(applicationContext) } catch (e: Exception) { e.printStackTrace() }
        val keepScreenOnFlow = Preferences.keepScreenOnFlow
        lifecycleScope.launch {
            keepScreenOnFlow.collect { WakeLockManager.applyKeepScreenOn(this@MainActivity, it) }
        }
        val keepAppOpenFlow = Preferences.keepAppOpenFlow
        lifecycleScope.launch {
            keepAppOpenFlow.collect { keepAppOpen ->
                if (keepAppOpen) WakeLockManager.acquirePartialWakeLock(applicationContext)
                else WakeLockManager.releasePartialWakeLock()
            }
        }
        val metaPrefs = getSharedPreferences("mp3tify_meta", Context.MODE_PRIVATE)
        val recoveryCheckDone = metaPrefs.getBoolean("recovery_check_done", false)
        setContent {
            val darkTheme by Preferences.darkThemeFlow.collectAsState(initial = true)
            val accentKey by Preferences.accentColorFlow.collectAsState(initial = "verde")
            var showRecoveryDialog by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) {
                if (!recoveryCheckDone) {
                    try {
                        val hasData = withContext(Dispatchers.IO) { Repository.get(applicationContext).hasUserData() }
                        if (hasData) showRecoveryDialog = true
                        else metaPrefs.edit().putBoolean("recovery_check_done", true).apply()
                    } catch (e: Exception) {
                        e.printStackTrace()
                        metaPrefs.edit().putBoolean("recovery_check_done", true).apply()
                    }
                }
            }
            Mp3tifyTheme(forceDark = darkTheme, accent = AccentColor.fromKey(accentKey)) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    ProvideAppLanguage { AppNav() }
                }
                if (showRecoveryDialog) {
                    AlertDialog(
                        onDismissRequest = { showRecoveryDialog = false; metaPrefs.edit().putBoolean("recovery_check_done", true).apply() },
                        title = { Text(t("recovery_title")) },
                        text = { Text(t("recovery_message")) },
                        confirmButton = { TextButton(onClick = { showRecoveryDialog = false; metaPrefs.edit().putBoolean("recovery_check_done", true).apply() }) { Text(t("recovery_keep")) } },
                        dismissButton = {
                            TextButton(onClick = {
                                showRecoveryDialog = false
                                lifecycleScope.launch {
                                    try { withContext(Dispatchers.IO) { Repository.get(applicationContext).wipeAllUserData() } } catch (e: Exception) { e.printStackTrace() }
                                    metaPrefs.edit().putBoolean("recovery_check_done", true).apply()
                                }
                            }) { Text(t("recovery_wipe")) }
                        }
                    )
                }
            }
        }
    }
    override fun onResume() {
        super.onResume()
        lifecycleScope.launch {
            try { val k = Preferences.keepScreenOnFirst(); WakeLockManager.applyKeepScreenOn(this@MainActivity, k) } catch (e: Exception) { e.printStackTrace() }
        }
    }
}
