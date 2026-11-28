package com.mp3tify

import android.Manifest
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.util.Rational
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.mp3tify.data.repository.Repository
import com.mp3tify.playback.PlaybackController
import com.mp3tify.playback.SleepTimerManager
import com.mp3tify.playback.WakeLockManager
import android.view.WindowManager
import com.mp3tify.data.model.displayArtist
import com.mp3tify.data.model.displayTitle
import com.mp3tify.ui.AppNav
import com.mp3tify.ui.components.AlbumArt
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
    companion object { private const val ACTION_PIP_PREV = "com.mp3tify.PIP_PREV"; private const val ACTION_PIP_PLAY_PAUSE = "com.mp3tify.PIP_PLAY_PAUSE"; private const val ACTION_PIP_NEXT = "com.mp3tify.PIP_NEXT" }
    private var isInPip by mutableStateOf(false)
    private val pipReceiver = object : BroadcastReceiver() { override fun onReceive(context: Context?, intent: Intent?) { when (intent?.action) { ACTION_PIP_PREV -> PlaybackController.skipToPrevious(); ACTION_PIP_PLAY_PAUSE -> if (PlaybackController.barMeta.value.isPlaying) PlaybackController.pause() else PlaybackController.play(); ACTION_PIP_NEXT -> PlaybackController.skipToNext() } } }
    override fun onCreate(savedInstanceState: Bundle?) {
        // #1 enableEdgeToEdge: hace las system bars transparentes Y le dice a Compose
        // que respete los WindowInsets. Sin esto, el TopAppBar (con iconos de search,
        // eq, queue) se dibuja DEBAJO de la status bar (donde esta bateria/hora/wifi)
        // en algunos modelos de celular. Con enableEdgeToEdge, Material 3 Scaffold
        // + TopAppBar agregan automaticamente el padding superior igual a la altura
        // de la status bar, y el PlayerBar inferior queda por encima de la navigation
        // bar (gestural nav).
        enableEdgeToEdge()
        // CRITICAL #1: backup explicito por si enableEdgeToEdge no activa bien en
        // algunos modelos. setDecorFitsSystemWindows(false) hace que el contenido de
        // la window se extienda bajo las system bars (edge-to-edge real). Sin esto,
        // los WindowInsets de Compose reportarian (0,0,0,0) y los paddings no aplican.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        super.onCreate(savedInstanceState)
        try { PlaybackController.connect(applicationContext) } catch (e: Exception) { e.printStackTrace() }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val f = IntentFilter().apply { addAction(ACTION_PIP_PREV); addAction(ACTION_PIP_PLAY_PAUSE); addAction(ACTION_PIP_NEXT) }
            ContextCompat.registerReceiver(this, pipReceiver, f, ContextCompat.RECEIVER_NOT_EXPORTED)
            addOnPictureInPictureModeChangedListener { isInPip = it.isInPictureInPictureMode }
            lifecycleScope.launch {
                var lastPip: Pair<Boolean, Boolean>? = null
                PlaybackController.barMeta.collect { meta -> val key = meta.isPlaying to (meta.currentSong != null); if (key != lastPip && (isInPip || Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)) { lastPip = key; try { setPictureInPictureParams(buildPipParams(key.first, key.second)) } catch (_: Exception) {} } }
            }
        }
        // CRITICAL FIX (app se congela + pierde cola tras call/kill):
        // Restaurar la ultima cola guardada para que el usuario pueda resumir
        // sin tener que volver a seleccionar canciones. La cola se persistio
        // en SharedPreferences cuando se llamo a setQueue().
        try {
            PlaybackController.restoreSavedQueue(Repository.get(applicationContext))
        } catch (e: Exception) { e.printStackTrace() }

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

        // CRITICAL (v2.5.6 anti-hibernacion): la hibernacion de Android ("Pausar
        // actividad en la app si no se usa") REVOCa los permisos. Sin READ_MEDIA_AUDIO
        // (o READ_EXTERNAL_STORAGE en Android 12-), ExoPlayer no puede leer los
        // archivos de audio y NADA reproduce — el bug "ya no reproduce nada despues
        // de un rato". Re-verificar en cada arranque y re-solicitar si fue revocado.
        val mediaPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        if (ContextCompat.checkSelfPermission(this, mediaPermission)
            != PackageManager.PERMISSION_GRANTED) {
            android.util.Log.w("MainActivity", "Media permission was revoked (app hibernation?), re-requesting: $mediaPermission")
            requestPermissions(arrayOf(mediaPermission), 1002)
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
                    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
                    val sideInsets = if (isLandscape) WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal).union(WindowInsets(left = 12.dp, right = 12.dp)) else WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)
                    ProvideAppLanguage {
                        Box(Modifier.fillMaxSize()) {
                            Box(Modifier.fillMaxSize().windowInsetsPadding(sideInsets)) { AppNav() }
                            if (isInPip) {
                                val meta by PlaybackController.barMeta.collectAsState()
                                val song = meta.currentSong
                                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(horizontal = 10.dp, vertical = 6.dp), contentAlignment = Alignment.TopCenter) {
                                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                        AlbumArt(song = song, size = 44.dp, modifier = Modifier.clip(RoundedCornerShape(8.dp)))
                                        Column(Modifier.weight(1f)) {
                                            Text(song?.displayTitle ?: t("app_name"), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            Text(song?.displayArtist ?: "", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                // CRITICAL FIX: diálogo de selección de idioma en la primera instalación.
                if (!isInPip && showLanguageDialog) {
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
                if (!isInPip && showRecoveryDialog) {
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


    @androidx.annotation.RequiresApi(Build.VERSION_CODES.O)
    private fun pipAction(action: String, reqCode: Int, iconRes: Int, title: String) = RemoteAction(android.graphics.drawable.Icon.createWithResource(this, iconRes), title, title, PendingIntent.getBroadcast(this, reqCode, Intent(action).setPackage(packageName), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
    @androidx.annotation.RequiresApi(Build.VERSION_CODES.O)
    private fun buildPipParams(isPlaying: Boolean, hasSong: Boolean): PictureInPictureParams {
        val b = PictureInPictureParams.Builder().setAspectRatio(Rational(21, 9)).setActions(listOf(pipAction(ACTION_PIP_PREV, 101, R.drawable.ic_pip_prev, "Previous"), pipAction(ACTION_PIP_PLAY_PAUSE, 102, if (isPlaying) R.drawable.ic_pip_pause else R.drawable.ic_pip_play, if (isPlaying) "Pause" else "Play"), pipAction(ACTION_PIP_NEXT, 103, R.drawable.ic_pip_next, "Next")))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) b.setAutoEnterEnabled(isPlaying && hasSong)
        return b.build()
    }
    override fun onUserLeaveHint() { super.onUserLeaveHint(); if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !isInPictureInPictureMode) { val m = PlaybackController.barMeta.value; if (m.isPlaying && m.currentSong != null) try { enterPictureInPictureMode(buildPipParams(true, true)) } catch (_: Exception) {} } }
    override fun onDestroy() { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) try { unregisterReceiver(pipReceiver) } catch (_: Exception) {}; super.onDestroy() }
    override fun onResume() {
        super.onResume()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) isInPip = isInPictureInPictureMode
        SleepTimerManager.attachActivity(this)
        WakeLockManager.refreshDeviceAdminStatus(this)
        val lp = window.attributes
        if (lp.screenBrightness != WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE) {
            lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            window.attributes = lp
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        ) {
            PlaybackController.refreshMediaNotification()
        }
        lifecycleScope.launch {
            try { val k = Preferences.keepScreenOnFirst(); WakeLockManager.applyKeepScreenOn(this@MainActivity, k) } catch (e: Exception) { e.printStackTrace() }
        }
    }
}
