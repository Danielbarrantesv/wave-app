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
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
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
import com.mp3tify.LastFmClient
import com.mp3tify.backup.BackupManager
import com.mp3tify.cloud.CloudBackupManager
import com.mp3tify.data.repository.Repository
import com.mp3tify.ui.components.ToggleRow
import com.mp3tify.ui.t
import com.mp3tify.ui.tf
import com.mp3tify.ui.theme.AccentColor
import com.mp3tify.util.AppLanguage
import com.mp3tify.util.Preferences
import kotlinx.coroutines.launch

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
    var wipeDoneMessage by remember { mutableStateOf<String?>(null) }
    var isRestoring by remember { mutableStateOf(false) }
    var restoreProgress by remember { mutableStateOf(0) }
    var showReselectFolderDialog by remember { mutableStateOf(false) }
    var isRemapping by remember { mutableStateOf(false) }
    var remapProgress by remember { mutableStateOf(0) }
    var remapResult by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var isUpdatingCovers by remember { mutableStateOf(false) }
    var coverProgress by remember { mutableStateOf(0) }
    var coverResult by remember { mutableStateOf<Pair<Int, Int>?>(null) }

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
                )
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

            // ---------------- Actualizar portadas (Last.fm) ----------------
            SectionHeader(t("update_covers"))
            Text(t("update_covers_desc"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp))
            Spacer(Modifier.height(4.dp))
            var lastFmKey by remember { mutableStateOf(LastFmClient.getApiKey(context) ?: "") }
            OutlinedTextField(value = lastFmKey, onValueChange = { lastFmKey = it }, label = { Text(t("lastfm_api_key")) }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp))
            Spacer(Modifier.height(8.dp))
            Button(onClick = { LastFmClient.setApiKey(context, lastFmKey.trim()); scope.launch { isUpdatingCovers = true; coverProgress = 0; try { val (u, t2) = LastFmClient.updateAllMetadata(context) { p -> coverProgress = p.coerceIn(0,100) }; coverResult = Pair(u,t2) } catch (e: Exception) { e.printStackTrace(); coverResult = Pair(0,0) } finally { isUpdatingCovers = false; coverProgress = 0 } } }, enabled = lastFmKey.isNotBlank() && !isUpdatingCovers, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                Icon(Icons.Filled.CloudDownload, contentDescription = null); Spacer(Modifier.width(6.dp)); Text(t("update_covers"))
            }

            // ---------------- Acerca de ----------------
            SectionHeader(t("section_about"))
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text("Wave", style = MaterialTheme.typography.titleMedium)
                Text(
                    "${t("about_version")} 2.4.0",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
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

        if (isUpdatingCovers) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.8f)), contentAlignment = Alignment.Center) {
                Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface) {
                    Column(Modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.height(16.dp))
                        Text(t("update_covers"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(4.dp))
                        Text("${coverProgress}%", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(12.dp))
                        LinearProgressIndicator(progress = coverProgress / 100f, modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.primary, trackColor = MaterialTheme.colorScheme.surfaceVariant)
                    }
                }
            }
        }

        coverResult?.let { (updated, total) ->
            AlertDialog(onDismissRequest = { coverResult = null }, title = { Text(t("update_covers")) }, text = { Text("$updated / $total") }, confirmButton = { TextButton(onClick = { coverResult = null }) { Text(t("ok")) } })
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
