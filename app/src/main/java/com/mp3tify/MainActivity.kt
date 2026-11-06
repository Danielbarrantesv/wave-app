package com.mp3tify

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.content.ContextCompat
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Language
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.mp3tify.data.repository.Repository
import com.mp3tify.playback.PlaybackController
import com.mp3tify.playback.WakeLockManager
import com.mp3tify.ui.AppNav
import com.mp3tify.ui.ProvideAppLanguage
import com.mp3tify.ui.t
import com.mp3tify.ui.theme.AccentColor
import com.mp3tify.ui.theme.Mp3tifyTheme
import com.mp3tify.util.AppLanguage
import com.mp3tify.util.Preferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // #1 enableEdgeToEdge: hace las system bars transparentes Y le dice a Compose
        // que respete los WindowInsets. Sin esto, el TopAppBar (con iconos de search,
        // eq, queue) se dibuja DEBAJO de la status bar (donde esta bateria/hora/wifi)
        // en algunos modelos de celular. Con enableEdgeToEdge, Material 3 Scaffold
        // + TopAppBar agregan automaticamente el padding superior igual a la altura
        // de la status bar, y el PlayerBar inferior queda por encima de la navigation
        // bar (gestural nav).
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        try { PlaybackController.connect(applicationContext) } catch (e: Exception) { e.printStackTrace() }

        // #2 POST_NOTIFICATIONS runtime permission (Android 13+): sin este permiso
        // concedido en runtime, la MediaStyle notification NO aparece en el quick
        // settings dropdown ni en el Dynamic Island de Xiaomi, aunque el canal este
        // creado. Se pide una sola vez; si el usuario la niega, la app funciona pero
        // sin tarjeta de reproduccion en el dropdown.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001)
            }
        }

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
        val languageSelectionDone = metaPrefs.getBoolean("language_selection_done", false)
        setContent {
            val darkTheme by Preferences.darkThemeFlow.collectAsState(initial = true)
            val accentKey by Preferences.accentColorFlow.collectAsState(initial = "verde")
            val scope = rememberCoroutineScope()
            var showLanguageDialog by remember { mutableStateOf(false) }
            var showRecoveryDialog by remember { mutableStateOf(false) }
            var languageDone by remember { mutableStateOf(languageSelectionDone) }

            // CRITICAL FIX: mostrar diálogo de idioma PRIMERO en la primera instalación.
            LaunchedEffect(Unit) {
                if (!languageDone) {
                    showLanguageDialog = true
                } else if (!recoveryCheckDone) {
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

            // Cuando el diálogo de idioma se cierra (showLanguageDialog=false) y
            // languageDone pasa a true, verificar recovery si no se ha hecho.
            LaunchedEffect(languageDone) {
                if (languageDone && !recoveryCheckDone && !showLanguageDialog) {
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
                // CRITICAL FIX: diálogo de selección de idioma en la primera instalación.
                if (showLanguageDialog) {
                    AlertDialog(
                        onDismissRequest = {
                            // Si cierra sin elegir, usar español por defecto.
                            showLanguageDialog = false
                            scope.launch { Preferences.setLanguage("es") }
                            metaPrefs.edit().putBoolean("language_selection_done", true).apply()
                            languageDone = true
                        },
                        title = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.Language, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp))
                            }
                        },
                        text = {
                            Column {
                                AppLanguage.entries.forEach { lang ->
                                    OutlinedButton(
                                        onClick = {
                                            showLanguageDialog = false
                                            scope.launch { Preferences.setLanguage(lang.code) }
                                            metaPrefs.edit().putBoolean("language_selection_done", true).apply()
                                            languageDone = true
                                        },
                                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                                    ) {
                                        Text(lang.displayName, fontWeight = FontWeight.Medium)
                                    }
                                }
                            }
                        },
                        confirmButton = {}
                    )
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
