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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
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
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.Sort
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
    val sortOrder by vm.sortOrder.collectAsState()

    var menuOpen by remember { mutableStateOf(false) }
    var sortMenuOpen by remember { mutableStateOf(false) }
    var showRename by remember { mutableStateOf(false) }
    var showDelete by remember { mutableStateOf(false) }
    var songToDelete by remember { mutableStateOf<Song?>(null) }
    // #2 State para los dialogs de "Add to playlist" y "Edit info" que ahora sí
    // funcionan desde el menu de 3 puntos de cada SongRow.
    var songForAddToPlaylist by remember { mutableStateOf<Song?>(null) }
    var showAddToPlaylistDialog by remember { mutableStateOf(false) }
    var songToEdit by remember { mutableStateOf<Song?>(null) }
    // State para el dialog "Create new playlist from selected" (#2)
    var showNewPlaylistFromSelectedDialog by remember { mutableStateOf(false) }
    // State para obtener las playlists existentes (para el dialog Add to playlist)
    val allPlaylists by remember { vm.allPlaylistsFlow }.collectAsState(initial = emptyList())

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
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
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
                    ),
                    windowInsets = WindowInsets.statusBars
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
                        // #2 NEW: opcion de crear playlist nueva con las seleccionadas
                        onCreatePlaylistFromSelected = { showNewPlaylistFromSelectedDialog = true },
                        // #4 FIX: el trash icon en una playlist SOLO remueve de la playlist,
                        // NO borra de la biblioteca. Antes llamaba a deleteSelectedFromLibrary
                        // que hacia DELETE FROM songs (la cancion desaparecia de todo el app).
                        // Ahora llama a removeSelectedFromPlaylist que solo hace DELETE FROM
                        // playlist_songs (la cancion queda en la biblioteca principal).
                        onRemoveFromPlaylist = { vm.removeSelectedFromPlaylist() },
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
                        // #3 Sort filter en playlist: 5 opciones (Añadidas/Reproducidas/Título/Artista/Duración)
                        if (songs.isNotEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            Box {
                                TextButton(onClick = { sortMenuOpen = true }) {
                                    Icon(
                                        Icons.Filled.Sort,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    Text(
                                        sortLabel(sortOrder),
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                                DropdownMenu(
                                    expanded = sortMenuOpen,
                                    onDismissRequest = { sortMenuOpen = false }
                                ) {
                                    DropdownMenuItem(
                                        text = { Text(t("sort_recently_added")) },
                                        onClick = { vm.setSortOrder(Repository.SortOrder.RECENTLY_ADDED); sortMenuOpen = false }
                                    )
                                    DropdownMenuItem(
                                        text = { Text(t("sort_recently_played")) },
                                        onClick = { vm.setSortOrder(Repository.SortOrder.RECENTLY_PLAYED); sortMenuOpen = false }
                                    )
                                    DropdownMenuItem(
                                        text = { Text(t("sort_title")) },
                                        onClick = { vm.setSortOrder(Repository.SortOrder.TITLE); sortMenuOpen = false }
                                    )
                                    DropdownMenuItem(
                                        text = { Text(t("sort_artist")) },
                                        onClick = { vm.setSortOrder(Repository.SortOrder.ARTIST); sortMenuOpen = false }
                                    )
                                    DropdownMenuItem(
                                        text = { Text(t("sort_duration")) },
                                        onClick = { vm.setSortOrder(Repository.SortOrder.DURATION); sortMenuOpen = false }
                                    )
                                }
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
                                // #2 FIX: onAddToPlaylist y onEditInfo estaban vacios ({ }).
                                // Ahora abren los mismos dialogs que LibraryScreen.
                                onAddToPlaylist = {
                                    songForAddToPlaylist = song
                                    showAddToPlaylistDialog = true
                                },
                                onEditInfo = { songToEdit = song },
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

    // #2 Add to playlist dialog — lista todas las playlists existentes para elegir
    if (showAddToPlaylistDialog) {
        AlertDialog(
            onDismissRequest = {
                showAddToPlaylistDialog = false
                songForAddToPlaylist = null
            },
            title = { Text(t("add_to_list")) },
            text = {
                Column {
                    if (allPlaylists.isEmpty()) {
                        Text(t("no_lists_yet"))
                    } else {
                        allPlaylists.forEach { pl ->
                            TextButton(
                                onClick = {
                                    songForAddToPlaylist?.let { song ->
                                        vm.addSongToExistingPlaylist(pl.id, song.id)
                                    }
                                    showAddToPlaylistDialog = false
                                    songForAddToPlaylist = null
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(pl.name, modifier = Modifier.weight(1f))
                                Text(
                                    tf("songs_count", 0),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = {
                    showAddToPlaylistDialog = false
                    songForAddToPlaylist = null
                }) { Text(t("cancel")) }
            }
        )
    }

    // #2 Edit song info dialog
    songToEdit?.let { song ->
        var editTitle by remember { mutableStateOf(song.title ?: "") }
        var editArtist by remember { mutableStateOf(song.artist ?: "") }
        var editAlbum by remember { mutableStateOf(song.album ?: "") }
        AlertDialog(
            onDismissRequest = { songToEdit = null },
            title = { Text(t("edit_info")) },
            text = {
                Column {
                    OutlinedTextField(
                        value = editTitle,
                        onValueChange = { editTitle = it },
                        label = { Text(t("title")) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = editArtist,
                        onValueChange = { editArtist = it },
                        label = { Text(t("artist")) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = editAlbum,
                        onValueChange = { editAlbum = it },
                        label = { Text(t("album")) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        try {
                            Repository.get(context).updateSongInfo(
                                song.id,
                                title = editTitle.trim().ifBlank { null },
                                artist = editArtist.trim().ifBlank { null },
                                album = editAlbum.trim().ifBlank { null }
                            )
                        } catch (e: Exception) { e.printStackTrace() }
                    }
                    songToEdit = null
                }) { Text(t("save")) }
            },
            dismissButton = {
                TextButton(onClick = { songToEdit = null }) { Text(t("cancel")) }
            }
        )
    }

    // #2 Create new playlist from selected songs dialog
    if (showNewPlaylistFromSelectedDialog) {
        var newName by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showNewPlaylistFromSelectedDialog = false },
            title = { Text(tf("create_with_count", selectedIds.size)) },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text(t("name")) },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (newName.isNotBlank()) {
                            val trimmed = newName.trim()
                            val capitalized = if (trimmed.isNotEmpty()) {
                                trimmed.substring(0, 1).uppercase() + trimmed.substring(1)
                            } else trimmed
                            vm.createPlaylistFromSelected(capitalized)
                        }
                        showNewPlaylistFromSelectedDialog = false
                    },
                    enabled = newName.isNotBlank()
                ) { Text(t("create")) }
            },
            dismissButton = {
                TextButton(onClick = { showNewPlaylistFromSelectedDialog = false }) {
                    Text(t("cancel"))
                }
            }
        )
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
                        // #3 Capitalizar primera letra al renombrar tambien.
                        if (newName.isNotBlank()) {
                            val trimmed = newName.trim()
                            val capitalized = if (trimmed.isNotEmpty()) {
                                trimmed.substring(0, 1).uppercase() + trimmed.substring(1)
                            } else trimmed
                            vm.rename(capitalized)
                        }
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
 * #3 Sort label para el dropdown de orden en PlaylistScreen.
 * Mismas 5 opciones que LibraryScreen.
 */
@Composable
private fun sortLabel(order: Repository.SortOrder): String = when (order) {
    Repository.SortOrder.RECENTLY_ADDED -> t("sort_recently_added")
    Repository.SortOrder.RECENTLY_PLAYED -> t("sort_recently_played")
    Repository.SortOrder.TITLE -> t("sort_title")
    Repository.SortOrder.ARTIST -> t("sort_artist")
    Repository.SortOrder.DURATION -> t("sort_duration")
}

/**
 * SelectionBanner fijo arriba de PlaylistScreen. Solo iconos (#1).
 *
 * Acciones:
 *  - Close: salir del modo selección
 *  - Count text: "N seleccionadas"
 *  - PlayArrow: reproducir solo las seleccionadas
 *  - Favorite: agregar las seleccionadas a favoritos (si no lo son ya)
 *  - QueueMusic: crear playlist nueva con las seleccionadas (#2)
 *  - Delete: remueve las seleccionadas de ESTA playlist (NO borra de la
 *    biblioteca — la canción queda en la lista principal). #4
 */
@Composable
private fun PlaylistSelectionBanner(
    count: Int,
    onPlaySelected: () -> Unit,
    onAddToFavorites: () -> Unit,
    onCreatePlaylistFromSelected: () -> Unit,
    onRemoveFromPlaylist: () -> Unit,
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
            // #2 Crear playlist nueva con las seleccionadas (QueueMusic)
            IconButton(onClick = onCreatePlaylistFromSelected) {
                Icon(
                    Icons.Filled.QueueMusic,
                    contentDescription = tf("create_with_count", count)
                )
            }
            // #4 Remover de esta playlist (Delete, error tint).
            // Solo hace DELETE FROM playlist_songs, la canción queda en songs table.
            IconButton(onClick = onRemoveFromPlaylist) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = t("remove_from_playlist"),
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}
