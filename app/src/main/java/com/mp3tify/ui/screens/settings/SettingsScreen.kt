package com.mp3tify.ui.screens.settings

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.BrightnessHigh
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.DriveEta
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.mp3tify.backup.BackupManager
import com.mp3tify.BuildConfig
import com.mp3tify.cloud.CloudBackupManager
import com.mp3tify.data.repository.Repository
import com.mp3tify.ui.components.ToggleRow
import com.mp3tify.ui.t
import com.mp3tify.ui.tf
import com.mp3tify.ui.theme.AccentColor
import com.mp3tify.util.AppLanguage
import com.mp3tify.util.Preferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenEqualizer: () -> Unit,
    onOpenDriveMode: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // keepScreenOn + keepAppOpen — CRITICAL — Do NOT remove this section.
    // CRITICAL — Do NOT remove: keepScreenOn defaults ON (key keep_screen_on).
    val keepScreenOn by Preferences.keepScreenOnFlow.collectAsState(initial = true)
    // CRITICAL — Do NOT remove: keepAppOpen defaults ON (key keep_app_open).
    val keepAppOpen by Preferences.keepAppOpenFlow.collectAsState(initial = true)

    val darkTheme by Preferences.darkThemeFlow.collectAsState(initial = true)
    val accentKey by Preferences.accentColorFlow.collectAsState(initial = "verde")
    val langCode by Preferences.languageFlow.collectAsState(initial = "es")

    var backupMessage by remember { mutableStateOf<String?>(null) }
    var accentMenuOpen by remember { mutableStateOf(false) }
    var langMenuOpen by remember { mutableStateOf(false) }
    var showWipeDialog by remember { mutableStateOf(false) }
    // #4 Log de errores: dialog que muestra el path del archivo generado
    var showLogDialog by remember { mutableStateOf(false) }
    var logFilePath by remember { mutableStateOf<String?>(null) }
    // CRITICAL (v2.5.15 diagnóstico): state para guardar logs en una carpeta
    // seleccionable por el usuario (SAF). El flujo anterior solo generaba el
    // log en cacheDir (no accesible sin root). Ahora el usuario puede:
    //   1. Click "Guardar en carpeta..." → SAF picker → el log se escribe
    //      directamente en la carpeta seleccionada.
    //   2. Click "Compartir" → Intent.ACTION_SEND con el último URI guardado.
    var savedLogUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var savedLogFileName by remember { mutableStateOf<String?>(null) }
    // SAF launcher para seleccionar carpeta de logs.
    val logFolderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: android.net.Uri? ->
        if (uri != null) {
            // Tomar permiso persistable para acceder a la carpeta después de
            // cerrar/reabrir la app.
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (_: SecurityException) {}
            scope.launch(Dispatchers.IO) {
                try {
                    val result = generateAndSaveLogToFolder(context, uri)
                    withContext(Dispatchers.Main) {
                        savedLogUri = result.first
                        savedLogFileName = result.second
                        logFilePath = result.second  // Para mostrar también en el diálogo
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    withContext(Dispatchers.Main) {
                        savedLogFileName = "Error: ${e.message}"
                    }
                }
            }
        }
    }
    var wipeDoneMessage by remember { mutableStateOf<String?>(null) }
    var isRestoring by remember { mutableStateOf(false) }
    var restoreProgress by remember { mutableStateOf(0) }
    var showReselectFolderDialog by remember { mutableStateOf(false) }
    var isRemapping by remember { mutableStateOf(false) }
    var remapProgress by remember { mutableStateOf(0) }
    var remapResult by remember { mutableStateOf<Pair<Int, Int>?>(null) }

    val backupFolderLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val ok = BackupManager.writeToFolder(context, uri.toString())
                backupMessage = if (ok) "OK" else "Error"
            }
        }
    }

    val restoreFileLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                isRestoring = true
                restoreProgress = 0
                try {
                    val backup = BackupManager.readFromFile(context, uri.toString())
                    val ok = backup != null && BackupManager.restore(context, backup) { pct ->
                        restoreProgress = pct.coerceIn(0, 100)
                    }
                    if (ok) {
                        // CRITICAL FIX: tras restaurar, verificar si las canciones
                        // son accesibles. Si no (URIs caducaron tras desinstalar),
                        // pedir al usuario que re-seleccione la carpeta de música.
                        val needsFolder = BackupManager.needsFolderAccess(context)
                        if (needsFolder) {
                            showReselectFolderDialog = true
                            backupMessage = null
                        } else {
                            backupMessage = "OK"
                        }
                    } else {
                        backupMessage = "Error"
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    backupMessage = "Error"
                } finally {
                    isRestoring = false
                    restoreProgress = 0
                }
            }
        }
    }

    val reselectFolderLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                isRemapping = true
                remapProgress = 0
                try {
                    val (remapped, total) = BackupManager.remapFilePaths(context, uri.toString()) { pct ->
                        remapProgress = pct.coerceIn(0, 100)
                    }
                    remapResult = Pair(remapped, total)
                    com.mp3tify.backup.BackupUriManager.saveTreeUri(context, uri)
                } catch (e: Exception) {
                    e.printStackTrace()
                    remapResult = Pair(0, 0)
                } finally {
                    isRemapping = false
                    remapProgress = 0
                }
            }
        }
    }

    val cloudUploadLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val ok = CloudBackupManager.uploadTo(context, uri)
                backupMessage = if (ok) "OK" else "Error"
            }
        }
    }

    val cloudDownloadLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val ok = CloudBackupManager.downloadAndRestore(context, uri)
                backupMessage = if (ok) "OK" else "Error"
            }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(t("settings")) },
                navigationIcon = {
                    IconButton(onClick = { if (!isRestoring) onBack() }) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = t("back"))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                ),
                windowInsets = WindowInsets.statusBars
            )
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 32.dp)
        ) {
            // ---------------- Reproducción ----------------
            SectionHeader(t("section_playback"))

            // CRITICAL — Do NOT remove: keepScreenOn toggle (default ON) must stay visible here.
            ToggleRow(
                label = t("keep_screen_on"),
                description = t("keep_screen_on_desc"),
                checked = keepScreenOn,
                onCheckedChange = { checked ->
                    scope.launch { Preferences.setKeepScreenOn(checked) }
                },
                leadingIcon = Icons.Filled.BrightnessHigh
            )

            // CRITICAL — Do NOT remove: keepAppOpen toggle (default ON) must stay visible here.
            ToggleRow(
                label = t("keep_app_open"),
                description = t("keep_app_open_desc"),
                checked = keepAppOpen,
                onCheckedChange = { checked ->
                    scope.launch { Preferences.setKeepAppOpen(checked) }
                },
                leadingIcon = Icons.Filled.PhoneAndroid
            )

            ActionRow(
                label = t("equalizer"),
                icon = Icons.Filled.Equalizer,
                onClick = onOpenEqualizer
            )
            ActionRow(
                label = t("drive_mode"),
                icon = Icons.Filled.DriveEta,
                onClick = onOpenDriveMode
            )

            // ---------------- Apariencia ----------------
            SectionHeader(t("section_appearance"))
            ToggleRow(
                label = t("dark_theme"),
                description = t("dark_theme_desc"),
                checked = darkTheme,
                onCheckedChange = { checked ->
                    scope.launch { Preferences.setDarkTheme(checked) }
                },
                leadingIcon = Icons.Filled.DarkMode
            )

            // Accent color selector
            Box {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { accentMenuOpen = true }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Filled.Palette,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(t("accent_color"), style = MaterialTheme.typography.bodyLarge)
                        Text(
                            t(AccentColor.fromKey(accentKey).displayKey),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    AccentDot(AccentColor.fromKey(accentKey).dark)
                    Spacer(Modifier.width(8.dp))
                    Icon(
                        Icons.Filled.ExpandMore,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                DropdownMenu(
                    expanded = accentMenuOpen,
                    onDismissRequest = { accentMenuOpen = false }
                ) {
                    AccentColor.entries.forEach { ac ->
                        DropdownMenuItem(
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    AccentDot(ac.dark)
                                    Spacer(Modifier.width(10.dp))
                                    Text(t(ac.displayKey))
                                }
                            },
                            onClick = {
                                scope.launch { Preferences.setAccentColor(ac.key) }
                                accentMenuOpen = false
                            }
                        )
                    }
                }
            }

            // ---------------- Idioma ----------------
            SectionHeader(t("section_language"))
            Box {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { langMenuOpen = true }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Filled.Language,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(t("section_language"), style = MaterialTheme.typography.bodyLarge)
                        Text(
                            AppLanguage.fromCode(langCode).displayName,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Icon(
                        Icons.Filled.ExpandMore,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                DropdownMenu(
                    expanded = langMenuOpen,
                    onDismissRequest = { langMenuOpen = false }
                ) {
                    AppLanguage.entries.forEach { lang ->
                        DropdownMenuItem(
                            text = { Text(t(lang.stringKey)) },
                            onClick = {
                                scope.launch { Preferences.setLanguage(lang.code) }
                                langMenuOpen = false
                            }
                        )
                    }
                }
            }

            // ---------------- Respaldo ----------------
            SectionHeader(t("section_backup"))
            Text(
                t("backup_desc"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
            Spacer(Modifier.height(4.dp))
            ActionRow(
                label = t("make_backup"),
                icon = Icons.Filled.Backup,
                onClick = { backupFolderLauncher.launch(null) }
            )
            ActionRow(
                label = t("restore_backup"),
                icon = Icons.Filled.Restore,
                onClick = { restoreFileLauncher.launch(arrayOf("*/*")) }
            )

            // Cloud backup
            Text(
                t("cloud_backup"),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
            )
            ActionRow(
                label = t("upload_to_drive"),
                icon = Icons.Filled.CloudUpload,
                onClick = { cloudUploadLauncher.launch("mp3tify_backup.json") }
            )
            ActionRow(
                label = t("download_from_drive"),
                icon = Icons.Filled.CloudDownload,
                onClick = { cloudDownloadLauncher.launch(arrayOf("*/*")) }
            )

            SectionHeader(t("section_data"))
            Text(
                t("wipe_data_desc"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
            Spacer(Modifier.height(4.dp))
            ActionRow(label = t("wipe_data"), icon = Icons.Filled.DeleteForever, onClick = { showWipeDialog = true })

            // ---------------- Acerca de ----------------
            SectionHeader(t("section_about"))
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text("Wave", style = MaterialTheme.typography.titleMedium)
                Text(
                    // CRITICAL FIX (v2.5.16): antes estaba hardcodeado "2.5.6"
                    // y nunca se actualizaba aunque el APK fuera de una versión
                    // más nueva. Ahora usa BuildConfig.VERSION_NAME que lee del
                    // build.gradle.kts automáticamente. El usuario puede confiar
                    // en este número para saber qué versión tiene instalada.
                    "${t("about_version")} ${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Daniel Barrantes Villarevia",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Hecho con el poderoso Z.ai GLM-5.2",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium
                )
            }
            // #4 Botón para generar log de errores
            ActionRow(
                label = t("generate_error_log"),
                icon = Icons.Filled.BugReport,
                onClick = { showLogDialog = true }
            )
        }

        if (isRestoring) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.8f)),
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surface
                ) {
                    Column(
                        Modifier.padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.height(16.dp))
                        Text(
                            t("loading_files"),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "${restoreProgress}%",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(12.dp))
                        LinearProgressIndicator(
                            progress = restoreProgress / 100f,
                            modifier = Modifier.fillMaxWidth(),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            t("loading_files_desc"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }

        if (showReselectFolderDialog) {
            AlertDialog(
                onDismissRequest = { showReselectFolderDialog = false },
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.WarningAmber, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(t("restore_needs_folder"))
                    }
                },
                text = { Text(t("restore_needs_folder_desc"), style = MaterialTheme.typography.bodyMedium) },
                confirmButton = {
                    TextButton(onClick = {
                        showReselectFolderDialog = false
                        try { reselectFolderLauncher.launch(null) } catch (e: Exception) { e.printStackTrace() }
                    }) {
                        Icon(Icons.Filled.Folder, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text(t("reselect_folder"))
                    }
                },
                dismissButton = { TextButton(onClick = { showReselectFolderDialog = false }) { Text(t("cancel")) } }
            )
        }

        if (isRemapping) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.8f)), contentAlignment = Alignment.Center) {
                Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface) {
                    Column(Modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.height(16.dp))
                        Text(t("reselect_folder"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(4.dp))
                        Text("${remapProgress}%", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(12.dp))
                        LinearProgressIndicator(progress = remapProgress / 100f, modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.primary, trackColor = MaterialTheme.colorScheme.surfaceVariant)
                    }
                }
            }
        }

        remapResult?.let { (remapped, total) ->
            AlertDialog(
                onDismissRequest = { remapResult = null },
                title = { Text(t("reselect_folder")) },
                text = { Text(tf("restore_remap_done", remapped, total)) },
                confirmButton = { TextButton(onClick = { remapResult = null }) { Text(t("ok")) } }
            )
        }

        backupMessage?.let { message ->
            AlertDialog(
                onDismissRequest = { backupMessage = null },
                title = { Text(t("section_backup")) },
                text = { Text(message) },
                confirmButton = { TextButton(onClick = { backupMessage = null }) { Text(t("ok")) } }
            )
        }
        if (showWipeDialog) {
            AlertDialog(
                onDismissRequest = { showWipeDialog = false },
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.WarningAmber, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(28.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(t("wipe_data"))
                    }
                },
                text = { Text(t("wipe_data_warning")) },
                confirmButton = {
                    val doneMsg = t("wipe_data_done")
                    TextButton(onClick = {
                        showWipeDialog = false
                        scope.launch {
                            try { Repository.get(context).wipeAllUserData(); wipeDoneMessage = doneMsg }
                            catch (e: Exception) { e.printStackTrace(); wipeDoneMessage = "Error" }
                        }
                    }) { Text(t("wipe_data_confirm")) }
                },
                dismissButton = { TextButton(onClick = { showWipeDialog = false }) { Text(t("cancel")) } }
            )
        }
        wipeDoneMessage?.let { message ->
            AlertDialog(
                onDismissRequest = { wipeDoneMessage = null },
                title = { Text(t("section_data")) },
                text = { Text(message) },
                confirmButton = { TextButton(onClick = { wipeDoneMessage = null }) { Text(t("ok")) } }
            )
        }

        // #4 Log de errores: dialog que genera un archivo con los logs de la app
        // CRITICAL (v2.5.15 diagnóstico): extendido para que el usuario pueda
        // seleccionar carpeta accesible vía SAF y compartir directamente.
        if (showLogDialog) {
            AlertDialog(
                onDismissRequest = { showLogDialog = false },
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.BugReport, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(t("generate_error_log"))
                    }
                },
                text = {
                    Column {
                        Text(t("error_log_desc"), style = MaterialTheme.typography.bodyMedium)
                        // Mostrar nombre del archivo guardado (en carpeta seleccionada)
                        if (savedLogFileName != null) {
                            Spacer(Modifier.height(12.dp))
                            Text(
                                t("error_log_saved_at") + ":",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                savedLogFileName!!,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Medium
                            )
                            // Botón compartir (solo si hay URI guardado)
                            if (savedLogUri != null) {
                                Spacer(Modifier.height(8.dp))
                                TextButton(
                                    onClick = { shareLogFile(context, savedLogUri!!) },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Icon(Icons.Filled.CloudUpload, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text(t("error_log_share"))
                                }
                            }
                        }
                        // Path del log en cacheDir (si se generó con el botón anterior)
                        if (logFilePath != null && savedLogFileName == null) {
                            Spacer(Modifier.height(12.dp))
                            Text(
                                t("error_log_path") + ":",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                logFilePath!!,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                },
                confirmButton = {
                    // CRITICAL (v2.5.15): botón principal ahora abre SAF picker
                    // para seleccionar carpeta accesible. El log se escribe ahí
                    // directamente (no en cacheDir).
                    TextButton(onClick = { logFolderLauncher.launch(null) }) {
                        Icon(Icons.Filled.Folder, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(t("error_log_save_to_folder"))
                    }
                },
                dismissButton = {
                    Row {
                        // Botón "Generar" (cacheDir) — alternativa para compat con
                        // versiones anteriores donde el usuario ya conoce el flujo.
                        TextButton(onClick = {
                            scope.launch(Dispatchers.IO) {
                                try {
                                    val log = generateErrorLog(context)
                                    withContext(Dispatchers.Main) {
                                        logFilePath = log
                                        savedLogFileName = null
                                        savedLogUri = null
                                    }
                                } catch (e: Exception) {
                                    e.printStackTrace()
                                    withContext(Dispatchers.Main) {
                                        logFilePath = "Error: ${e.message}"
                                    }
                                }
                            }
                        }) { Text(t("error_log_generate")) }
                        Spacer(Modifier.width(4.dp))
                        TextButton(onClick = { showLogDialog = false }) { Text(t("close")) }
                    }
                }
            )
        }
    }
}

/**
 * CRITICAL (v2.5.15 diagnóstico): genera el log de errores y lo escribe
 * directamente en una carpeta seleccionada por el usuario vía SAF.
 *
 * El flujo es:
 *  1. Generar el contenido del log (mismo que generateErrorLog pero sin escribir
 *     a cacheDir).
 *  2. Crear un archivo wave_error_log_<timestamp>.txt dentro del tree URI
 *     seleccionado por el usuario, usando DocumentsContract.createDocument.
 *  3. Escribir el contenido al URI del archivo recién creado vía
 *     contentResolver.openOutputStream.
 *
 * Retorna (uri del archivo, nombre del archivo) para que el UI pueda mostrar
 * el nombre y ofrecer compartirlo.
 */
private suspend fun generateAndSaveLogToFolder(
    context: android.content.Context,
    folderUri: android.net.Uri
): Pair<android.net.Uri, String> {
    return withContext(Dispatchers.IO) {
        // 1. Generar contenido del log
        val timestamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())
        val fileName = "wave_error_log_$timestamp.txt"
        val logContent = buildLogContent(context)

        // 2. CRITICAL FIX (v2.5.17): convertir tree URI → document URI.
        // OpenDocumentTree devuelve un tree URI (carpeta) con scheme
        // 'content://com.android.externalstorage.documents/tree/...'.
        // DocumentsContract.createDocument requiere un DOCUMENT URI, no un
        // tree URI. Sin esta conversión, createDocument devuelve null y el
        // usuario ve 'Error: Invalid URI'.
        // Pasos:
        //   a) getTreeDocumentId(treeUri) → "primary:Music" (docId del árbol)
        //   b) buildDocumentUriUsingTree(treeUri, docId) → URI del documento
        //      "primario" dentro del árbol (la carpeta misma como documento)
        //   c) createDocument(parentDocUri, mime, fileName) → crea el archivo
        //      dentro de esa carpeta y devuelve el URI del archivo creado
        val treeDocId = android.provider.DocumentsContract.getTreeDocumentId(folderUri)
        val parentDocUri = android.provider.DocumentsContract.buildDocumentUriUsingTree(folderUri, treeDocId)
            ?: throw java.io.IOException("No se pudo construir el document URI de la carpeta seleccionada")
        val createdUri = android.provider.DocumentsContract.createDocument(
            context.contentResolver,
            parentDocUri,
            "text/plain",
            fileName
        ) ?: throw java.io.IOException("No se pudo crear el archivo en la carpeta seleccionada (createDocument devolvió null)")

        // 3. Escribir el contenido
        context.contentResolver.openOutputStream(createdUri, "w")?.use { out ->
            out.write(logContent.toByteArray())
            out.flush()
        } ?: throw java.io.IOException("No se pudo abrir el archivo para escritura")

        Pair(createdUri, fileName)
    }
}

/**
 * Construye el contenido del log de errores: header con info del dispositivo
 * + captura de logcat filtrado por tags relevantes.
 */
private fun buildLogContent(context: android.content.Context): String {
    val logBuilder = StringBuilder()
    logBuilder.appendLine("=== Wave Error Log ===")
    logBuilder.appendLine("Version: ${com.mp3tify.BuildConfig.VERSION_NAME} (code ${com.mp3tify.BuildConfig.VERSION_CODE})")
    logBuilder.appendLine("Date: ${java.util.Date()}")
    logBuilder.appendLine("Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
    logBuilder.appendLine("Android: ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})")
    logBuilder.appendLine()
    logBuilder.appendLine("=== Logs ===")
    logBuilder.appendLine()
    try {
        // Capturar logcat del proceso actual filtrado por tags relevantes
        val pid = android.os.Process.myPid()
        val tags = listOf(
            "PlaybackController",
            "EqualizerManager",
            "MediaPlaybackService",
            "MP3tifyApp",
            "MainActivity",
            "AndroidRuntime",
            "System.err",
            "Wave"
        )
        val command = mutableListOf("logcat", "-d", "--pid=$pid")
        for (tag in tags) {
            command.add("$tag:*")
        }
        command.add("*:S")  // silenciar todo lo demas
        val process = Runtime.getRuntime().exec(command.toTypedArray())
        val reader = java.io.BufferedReader(java.io.InputStreamReader(process.inputStream))
        var line: String?
        while (reader.readLine().also { line = it } != null) {
            logBuilder.appendLine(line)
        }
        reader.close()
        process.destroy()
    } catch (e: Exception) {
        logBuilder.appendLine("Error capturing logcat: ${e.message}")
    }
    logBuilder.appendLine()
    logBuilder.appendLine("=== End of Log ===")
    return logBuilder.toString()
}

/**
 * CRITICAL (v2.5.15 diagnóstico): comparte el archivo de log vía Intent.ACTION_SEND.
 * Abre el menú de compartir del sistema (Telegram, email, WhatsApp, etc.) con
 * el archivo .txt adjunto. El usuario puede mandárselo al dev para diagnóstico.
 */
private fun shareLogFile(context: android.content.Context, fileUri: android.net.Uri) {
    try {
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Wave error log")
            putExtra(Intent.EXTRA_STREAM, fileUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(shareIntent, context.getString(com.mp3tify.R.string.app_name) + " — log").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
    } catch (e: Exception) {
        e.printStackTrace()
    }
}

/**
 * Genera un archivo de log con los errores de la app. Captura logcat filtrado
 * por los tags relevantes (PlaybackController, EqualizerManager, MediaPlaybackService,
 * AndroidRuntime) y lo guarda en cacheDir/wave_error_log_<timestamp>.txt.
 *
 * El archivo se puede compartir via el intent de compartir archivos.
 */
private suspend fun generateErrorLog(context: android.content.Context): String {
    return withContext(Dispatchers.IO) {
        val timestamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())
        val logFile = java.io.File(context.cacheDir, "wave_error_log_$timestamp.txt")
        val tags = listOf(
            "PlaybackController",
            "EqualizerManager",
            "MediaPlaybackService",
            "AndroidRuntime",
            "System.err",
            "Wave"
        )
        val logBuilder = StringBuilder()
        logBuilder.appendLine("=== Wave Error Log ===")
        logBuilder.appendLine("Version: 2.5.6")
        logBuilder.appendLine("Date: ${java.util.Date()}")
        logBuilder.appendLine("Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
        logBuilder.appendLine("Android: ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})")
        logBuilder.appendLine()
        logBuilder.appendLine("=== Logs ===")
        logBuilder.appendLine()
        try {
            // Capturar logcat via Runtime.exec
            val pid = android.os.Process.myPid()
            val command = mutableListOf("logcat", "-d", "--pid=$pid")
            // Filtrar por tags relevantes
            for (tag in tags) {
                command.add("$tag:*")
            }
            command.add("*:S")  // silenciar todo lo demas
            val process = Runtime.getRuntime().exec(command.toTypedArray())
            val reader = java.io.BufferedReader(java.io.InputStreamReader(process.inputStream))
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                logBuilder.appendLine(line)
            }
            reader.close()
            process.destroy()
        } catch (e: Exception) {
            logBuilder.appendLine("Error capturing logcat: ${e.message}")
        }
        logBuilder.appendLine()
        logBuilder.appendLine("=== End of Log ===")
        logFile.writeText(logBuilder.toString())
        logFile.absolutePath
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 8.dp)
    )
}

@Composable
private fun ActionRow(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(16.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun AccentDot(color: Color, size: androidx.compose.ui.unit.Dp = 20.dp) {
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(color)
    )
}
