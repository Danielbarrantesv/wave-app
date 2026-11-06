package com.mp3tify.ui.screens.playlist

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.PlaylistRemove
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mp3tify.data.model.Song
import com.mp3tify.data.repository.Repository
import com.mp3tify.playback.PlaybackController
import com.mp3tify.ui.components.CollageCover
import com.mp3tify.ui.components.PlayerBar
import com.mp3tify.ui.components.ScrollbarLazyColumn
import com.mp3tify.ui.I18n
import com.mp3tify.ui.components.SongRow
import com.mp3tify.ui.t
import com.mp3tify.ui.tf
import com.mp3tify.ui.viewmodel.PlaylistViewModel
import kotlinx.coroutines.launch

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun PlaylistScreen(
    playlistId: Long,
    onBack: () -> Unit,
    onOpenNowPlaying: () -> Unit
) {
    val context = LocalContext.current
    val vm = remember(playlistId) { PlaylistViewModel(context.applicationContext, playlistId) }
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    val playbackState by PlaybackController.state.collectAsState()
    val playlist by vm.playlist.collectAsState()
    val songs by vm.songs.collectAsState()
    val importing by vm.importing.collectAsState()
    val importProgress by vm.importProgress.collectAsState()
    val selectionMode by vm.selectionMode.collectAsState()
    val selectedIds by vm.selectedIds.collectAsState()

    var menuOpen by remember { mutableStateOf(false) }
    var showRename by remember { mutableStateOf(false) }
    var showDelete by remember { mutableStateOf(false) }
    var songToDelete by remember { mutableStateOf<Song?>(null) }

    val isFavorites = playlist?.name == Repository.FAVORITES_NAME

    fun onImportDone(result: Repository.ImportResult?) {
        scope.launch {
            val msg = when {
                result == null -> I18n.t("no_results")
                result.added > 0 -> I18n.tf("added_songs", result.added)
                result.total == 0 -> I18n.t("no_music_found")
                else -> I18n.t("all_already")
            }
            snackbarHostState.showSnackbar(msg)
        }
    }

    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
                )
            } catch (e: Exception) {
                e.printStackTrace()
            }
            vm.setFolder(uri.toString())
            vm.importFolder(uri.toString()) { result -> onImportDone(result) }
        }
    }

    val filesPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        if (uris.isNotEmpty()) {
            vm.importFiles(uris.map { it.toString() }) { result -> onImportDone(result) }
        }
    }

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbarHostState) },
            topBar = {
                // STICKY top bar.
                TopAppBar(
                    title = {
                        Text(
                            playlist?.name ?: t("playlist"),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.Filled.ArrowBack, contentDescription = t("back"))
                        }
                    },
                    actions = {
                        Box {
                            IconButton(onClick = { menuOpen = true }) {
                                Icon(Icons.Filled.MoreVert, contentDescription = t("more_options"))
                            }
                            DropdownMenu(
                                expanded = menuOpen,
                                onDismissRequest = { menuOpen = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text(t("add_music")) },
                                    onClick = {
                                        menuOpen = false
                                        try { filesPicker.launch("audio/*") } catch (e: Exception) { e.printStackTrace() }
                                    },
                                    leadingIcon = { Icon(Icons.Filled.MusicNote, contentDescription = null) }
                                )
                                DropdownMenuItem(
                                    text = { Text(t("add_folder")) },
                                    onClick = {
                                        menuOpen = false
                                        folderPicker.launch(null)
                                    },
                                    leadingIcon = { Icon(Icons.Filled.Folder, contentDescription = null) }
                                )
                                // Rename/Delete are hidden for the protected "Favoritos".
                                if (!isFavorites) {
                                    DropdownMenuItem(
                                        text = { Text(t("rename")) },
                                        onClick = {
                                            menuOpen = false
                                            showRename = true
                                        },
                                        leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) }
                                    )
                                    DropdownMenuItem(
                                        text = { Text(t("delete")) },
                                        onClick = {
                                            menuOpen = false
                                            showDelete = true
                                        },
                                        leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) }
                                    )
                                }
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background
                    )
                )
            },
            bottomBar = {
                PlayerBar(state = playbackState, onOpenNowPlaying = onOpenNowPlaying)
            }
        ) { padding ->
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                // SelectionBanner fijo arriba (NO scrollea con la lista) — #1
                if (selectionMode) {
                    PlaylistSelectionBanner(
                        count = selectedIds.size,
                        onPlaySelected = { vm.playSelected() },
                        onAddToFavorites = {
                            scope.launch {
                                selectedIds.toList().forEach { id ->
                                    try {
                                        val s = songs.firstOrNull { it.id == id }
                                        if (s != null && !s.isFavorite) vm.toggleFavorite(s)
                                    } catch (e: Exception) { e.printStackTrace() }
                                }
                                vm.exitSelectionMode()
                            }
                        },
                        onRemoveFromPlaylist = { vm.removeSelectedFromPlaylist() },
                        onDeleteFromLibrary = { vm.deleteSelectedFromLibrary() },
                        onExit = { vm.exitSelectionMode() }
                    )
                }

                ScrollbarLazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 96.dp)
                ) {
                item {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        CollageCover(songs = songs, seed = playlistId, size = 160.dp)
                        Spacer(Modifier.height(12.dp))
                        Row {
                            Button(
                                onClick = { vm.playAll(false) },
                                enabled = songs.isNotEmpty()
                            ) {
                                Icon(Icons.Filled.PlayArrow, contentDescription = null)
                                Spacer(Modifier.width(6.dp))
                                Text(t("play_all"))
                            }
                            Spacer(Modifier.width(12.dp))
                            OutlinedButton(
                                onClick = { vm.playAll(true) },
                                enabled = songs.isNotEmpty()
                            ) {
                                Icon(Icons.Filled.Shuffle, contentDescription = null)
                                Spacer(Modifier.width(6.dp))
                                Text(t("shuffle"))
                            }
                        }
                        // Siempre mostrar 2 botones: agregar música + agregar carpeta.
                        Spacer(Modifier.height(8.dp))
                        Row {
                            OutlinedButton(onClick = {
                                try { filesPicker.launch("audio/*") } catch (e: Exception) { e.printStackTrace() }
                            }) {
                                Icon(Icons.Filled.MusicNote, contentDescription = null)
                                Spacer(Modifier.width(6.dp))
                                Text(t("add_music"))
                            }
                            Spacer(Modifier.width(12.dp))
                            OutlinedButton(onClick = { folderPicker.launch(null) }) {
                                Icon(Icons.Filled.LibraryMusic, contentDescription = null)
                                Spacer(Modifier.width(6.dp))
                                Text(t("add_folder"))
                            }
                        }
                        if (playlist?.folderUri != null) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                t("folder_import_hint"),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                if (songs.isEmpty()) {
                    item {
                        Text(
                            t("no_results"),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(24.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                } else {
                    items(songs, key = { it.id }) { song ->
                        val isSelected = selectedIds.contains(song.id)
                        Box(
                            Modifier.background(
                                if (isSelected) {
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                                } else {
                                    Color.Transparent
                                }
                            )
                        ) {
                            SongRow(
                                song = song,
                                onClick = {
                                    if (selectionMode) {
                                        vm.toggleSelected(song.id)
                                    } else {
                                        vm.play(song)
                                        onOpenNowPlaying()
                                    }
                                },
                                onLongClick = { vm.toggleSelected(song.id) },
                                onToggleFavorite = { vm.toggleFavorite(song) },
                                onAddToPlaylist = { },
                                onEditInfo = { },
                                onDelete = { songToDelete = song }
                            )
                        }
                    }
                }
                }
            }
        }

        if (importing) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.7f)),
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
                            "${importProgress}%",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(12.dp))
                        androidx.compose.material3.LinearProgressIndicator(
                            progress = importProgress / 100f,
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
    }

    // Rename dialog
    if (showRename) {
        var newName by remember { mutableStateOf(playlist?.name ?: "") }
        AlertDialog(
            onDismissRequest = { showRename = false },
            title = { Text(t("rename")) },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text(t("new_name")) },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (newName.isNotBlank()) vm.rename(newName.trim())
                        showRename = false
                    },
                    enabled = newName.isNotBlank()
                ) { Text(t("save")) }
            },
            dismissButton = {
                TextButton(onClick = { showRename = false }) { Text(t("cancel")) }
            }
        )
    }

    // Delete confirmation
    if (showDelete) {
        AlertDialog(
            onDismissRequest = { showDelete = false },
            title = { Text(t("delete")) },
            text = { Text(t("delete_playlist_confirm")) },
            confirmButton = {
                TextButton(onClick = {
                    showDelete = false
                    vm.delete { onBack() }
                }) { Text(t("delete")) }
            },
            dismissButton = {
                TextButton(onClick = { showDelete = false }) { Text(t("cancel")) }
            }
        )
    }

    // Remove song from playlist confirmation
    songToDelete?.let { song ->
        AlertDialog(
            onDismissRequest = { songToDelete = null },
            title = { Text(t("delete_song")) },
            text = { Text(t("delete_song_confirm")) },
            confirmButton = {
                TextButton(onClick = {
                    vm.removeSongFromPlaylist(song)
                    songToDelete = null
                }) { Text(t("delete")) }
            },
            dismissButton = {
                TextButton(onClick = { songToDelete = null }) { Text(t("cancel")) }
            }
        )
    }
}

/**
 * SelectionBanner fijo arriba de PlaylistScreen. Solo iconos (#1).
 *
 * Acciones:
 *  - Close: salir del modo selección
 *  - Count text: "N seleccionadas"
 *  - PlayArrow: reproducir solo las seleccionadas
 *  - Favorite: agregar las seleccionadas a favoritos (si no lo son ya)
 *  - PlaylistRemove: quitar las seleccionadas de esta playlist
 *  - Delete: borrar las seleccionadas de la biblioteca completa
 */
@Composable
private fun PlaylistSelectionBanner(
    count: Int,
    onPlaySelected: () -> Unit,
    onAddToFavorites: () -> Unit,
    onRemoveFromPlaylist: () -> Unit,
    onDeleteFromLibrary: () -> Unit,
    onExit: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        tonalElevation = 4.dp,
        shadowElevation = 4.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onExit) {
                Icon(Icons.Filled.Close, contentDescription = t("cancel"))
            }
            Text(
                tf("selected_count", count),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                maxLines = 1
            )
            // Reproducir seleccionadas
            IconButton(onClick = onPlaySelected) {
                Icon(Icons.Filled.PlayArrow, contentDescription = t("play_all"))
            }
            // Agregar a favoritos (corazón, accent)
            IconButton(onClick = onAddToFavorites) {
                Icon(
                    Icons.Filled.Favorite,
                    contentDescription = t("add_to_favorites"),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            // Quitar de esta playlist (PlaylistRemove)
            IconButton(onClick = onRemoveFromPlaylist) {
                Icon(Icons.Filled.PlaylistRemove, contentDescription = t("remove_from_playlist"))
            }
            // Borrar de la biblioteca (Delete, error tint)
            IconButton(onClick = onDeleteFromLibrary) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = t("delete_song"),
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}
