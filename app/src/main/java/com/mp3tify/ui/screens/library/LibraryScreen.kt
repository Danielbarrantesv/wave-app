package com.mp3tify.ui.screens.library

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DriveEta
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
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
import androidx.compose.ui.window.Dialog
import androidx.documentfile.provider.DocumentFile
import com.mp3tify.data.model.Playlist
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
import com.mp3tify.ui.viewmodel.LibraryViewModel
import kotlinx.coroutines.launch

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    onOpenSearch: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenNowPlaying: () -> Unit,
    onOpenPlaylist: (Long) -> Unit,
    onOpenDriveMode: () -> Unit
) {
    val context = LocalContext.current
    val vm = remember { LibraryViewModel() }
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    val playbackState by PlaybackController.state.collectAsState()
    val songs by vm.songs.collectAsState()
    val playlists by vm.playlists.collectAsState()
    val selectionMode by vm.selectionMode.collectAsState()
    val selectedIds by vm.selectedIds.collectAsState()
    val importing by vm.importing.collectAsState()
    val importProgress by vm.importProgress.collectAsState()
    val sortOrder by vm.sortOrder.collectAsState()

    var showNewPlaylistDialog by remember { mutableStateOf(false) }
    var showAddToPlaylistDialog by remember { mutableStateOf(false) }
    var songForAddToPlaylist by remember { mutableStateOf<Song?>(null) }
    var songToEdit by remember { mutableStateOf<Song?>(null) }
    var songToDelete by remember { mutableStateOf<Song?>(null) }
    var sortMenuOpen by remember { mutableStateOf(false) }

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

    val pickFolderLauncher = rememberLauncherForActivityResult(
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
            vm.importFromFolder(uri.toString()) { result -> onImportDone(result) }
        }
    }

    val pickFilesLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        if (uris.isNotEmpty()) {
            vm.importFiles(uris.map { it.toString() }) { result -> onImportDone(result) }
        }
    }

    var showAddMusicDialog by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbarHostState) },
            topBar = {
                // STICKY top bar (Scaffold pins it above the scrolling content).
                TopAppBar(
                    title = { Text(t("app_name"), style = MaterialTheme.typography.titleLarge) },
                    actions = {
                        IconButton(onClick = onOpenSearch) {
                            Icon(Icons.Filled.Search, contentDescription = t("search_placeholder"))
                        }
                        IconButton(onClick = { showAddMusicDialog = true }) {
                            Icon(Icons.Filled.LibraryMusic, contentDescription = t("add_music"))
                        }
                        IconButton(onClick = onOpenDriveMode) {
                            Icon(Icons.Filled.DriveEta, contentDescription = t("drive_mode"))
                        }
                        IconButton(onClick = onOpenSettings) {
                            Icon(Icons.Filled.Settings, contentDescription = t("settings"))
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background
                    )
                )
            },
            bottomBar = {
                PlayerBar(state = playbackState, onOpenNowPlaying = onOpenNowPlaying)
            },
            floatingActionButton = {
                ExtendedFloatingActionButton(
                    onClick = { showAddMusicDialog = true },
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    text = { Text(t("add_music")) },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = Color.Black
                )
            }
        ) { padding ->
            ScrollbarLazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    top = padding.calculateTopPadding(),
                    bottom = padding.calculateBottomPadding() + 96.dp
                )
            ) {
                if (selectionMode) {
                    item {
                        SelectionBanner(
                            count = selectedIds.size,
                            onCreateList = { showNewPlaylistDialog = true },
                            onAddTo = { showAddToPlaylistDialog = true },
                            onExit = { vm.exitSelectionMode() }
                        )
                    }
                }

                item {
                    CollageHeader(
                        songs = songs,
                        onPlayAll = { vm.playAll(false) },
                        onShuffleAll = { vm.playAll(true) }
                    )
                }

                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            t("your_lists"),
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = { showNewPlaylistDialog = true }) {
                            Icon(
                                Icons.Filled.Add,
                                contentDescription = t("new_list"),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }

                if (playlists.isEmpty()) {
                    item {
                        Text(
                            t("no_lists_yet"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                        )
                    }
                } else {
                    item {
                        LazyRow(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 4.dp),
                            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp)
                        ) {
                            items(playlists, key = { "pl_${it.id}" }) { pl ->
                                PlaylistCard(
                                    playlist = pl,
                                    onClick = { onOpenPlaylist(pl.id) }
                                )
                            }
                        }
                    }
                }

                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(t("library_title"), style = MaterialTheme.typography.titleMedium)
                            Text(
                                tf("songs_count", songs.size),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Box {
                            TextButton(onClick = { sortMenuOpen = true }) {
                                Icon(
                                    Icons.Filled.Sort,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(sortLabel(sortOrder), style = MaterialTheme.typography.bodySmall)
                            }
                            DropdownMenu(
                                expanded = sortMenuOpen,
                                onDismissRequest = { sortMenuOpen = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text(t("sort_recently_added")) },
                                    onClick = {
                                        vm.setSortOrder(Repository.SortOrder.RECENTLY_ADDED)
                                        sortMenuOpen = false
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text(t("sort_recently_played")) },
                                    onClick = {
                                        vm.setSortOrder(Repository.SortOrder.RECENTLY_PLAYED)
                                        sortMenuOpen = false
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text(t("sort_title")) },
                                    onClick = {
                                        vm.setSortOrder(Repository.SortOrder.TITLE)
                                        sortMenuOpen = false
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text(t("sort_artist")) },
                                    onClick = {
                                        vm.setSortOrder(Repository.SortOrder.ARTIST)
                                        sortMenuOpen = false
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text(t("sort_duration")) },
                                    onClick = {
                                        vm.setSortOrder(Repository.SortOrder.DURATION)
                                        sortMenuOpen = false
                                    }
                                )
                            }
                        }
                    }
                }

                if (songs.isEmpty()) {
                    item {
                        EmptyStateCard(onAddMusic = { showAddMusicDialog = true })
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

        // Global "Cargando archivos…" overlay while importing.
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

    // ---------------- Dialogs ----------------
    if (showAddMusicDialog) {
        Dialog(onDismissRequest = { showAddMusicDialog = false }) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 6.dp,
                modifier = Modifier.fillMaxWidth(0.92f)
            ) {
                Column(
                    Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(t("add_music"), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        t("add_files_desc"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Button(
                            onClick = {
                                showAddMusicDialog = false
                                try { pickFilesLauncher.launch("audio/*") } catch (e: Exception) { e.printStackTrace() }
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Filled.MusicNote, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text(t("music"))
                        }
                        OutlinedButton(
                            onClick = {
                                showAddMusicDialog = false
                                try { pickFolderLauncher.launch(null) } catch (e: Exception) { e.printStackTrace() }
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Filled.Folder, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text(t("folder"))
                        }
                    }
                }
            }
        }
    }

    if (showNewPlaylistDialog) {
        NewPlaylistDialog(
            presetSongIds = selectedIds.toList(),
            onCreate = { name, folderUri ->
                vm.createPlaylist(name, selectedIds.toList(), folderUri)
                vm.exitSelectionMode()
                showNewPlaylistDialog = false
            },
            onDismiss = { showNewPlaylistDialog = false }
        )
    }

    if (showAddToPlaylistDialog) {
        AddToPlaylistDialog(
            playlists = playlists,
            onPick = { playlistId ->
                val ids = listOfNotNull(songForAddToPlaylist?.id).ifEmpty { selectedIds.toList() }
                vm.addSongsToExistingPlaylist(playlistId, ids)
                vm.exitSelectionMode()
                showAddToPlaylistDialog = false
                songForAddToPlaylist = null
            },
            onNewList = {
                showAddToPlaylistDialog = false
                showNewPlaylistDialog = true
            },
            onDismiss = {
                showAddToPlaylistDialog = false
                songForAddToPlaylist = null
            }
        )
    }

    songToEdit?.let { song ->
        EditSongInfoDialog(
            song = song,
            onSave = { newTitle, newArtist, newAlbum ->
                vm.updateSongInfo(song, newTitle, newArtist, newAlbum)
                songToEdit = null
            },
            onDismiss = { songToEdit = null }
        )
    }

    songToDelete?.let { song ->
        AlertDialog(
            onDismissRequest = { songToDelete = null },
            title = { Text(t("delete_song")) },
            text = { Text(t("delete_song_confirm")) },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteSong(song)
                    songToDelete = null
                }) { Text(t("delete")) }
            },
            dismissButton = {
                TextButton(onClick = { songToDelete = null }) { Text(t("cancel")) }
            }
        )
    }
}

@Composable
private fun sortLabel(order: Repository.SortOrder): String = when (order) {
    Repository.SortOrder.RECENTLY_ADDED -> t("sort_recently_added")
    Repository.SortOrder.RECENTLY_PLAYED -> t("sort_recently_played")
    Repository.SortOrder.TITLE -> t("sort_title")
    Repository.SortOrder.ARTIST -> t("sort_artist")
    Repository.SortOrder.DURATION -> t("sort_duration")
}

@Composable
private fun SelectionBanner(
    count: Int,
    onCreateList: () -> Unit,
    onAddTo: () -> Unit,
    onExit: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onExit) {
                Icon(Icons.Filled.Close, contentDescription = t("cancel"))
            }
            Text(
                tf("selected_count", count),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            TextButton(onClick = onCreateList) {
                Text(tf("create_with_count", count), maxLines = 1)
            }
            TextButton(onClick = onAddTo) { Text(t("add_to_list"), maxLines = 1) }
        }
    }
}

@Composable
private fun CollageHeader(
    songs: List<Song>,
    onPlayAll: () -> Unit,
    onShuffleAll: () -> Unit
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        CollageCover(songs = songs, seed = 42L, size = 140.dp)
        Spacer(Modifier.height(12.dp))
        Text(
            t("your_library"),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        Text(
            t("library_title"),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            tf("songs_count", songs.size),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(12.dp))
        Row {
            Button(onClick = onPlayAll, enabled = songs.isNotEmpty()) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text(t("play_all"))
            }
            Spacer(Modifier.width(12.dp))
            OutlinedButton(onClick = onShuffleAll, enabled = songs.isNotEmpty()) {
                Icon(Icons.Filled.Shuffle, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text(t("shuffle"))
            }
        }
    }
}

/**
 * Horizontal playlist card — cover on top, text BELOW, with a heart badge
 * overlay for the protected "Favoritos" playlist. Each card observes its own
 * songs so the collage reflects what's actually inside the playlist.
 */
@Composable
private fun PlaylistCard(
    playlist: Playlist,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    val repo = remember { Repository.get(context) }
    val songs by remember(playlist.id) {
        repo.observePlaylistSongs(playlist.id)
    }.collectAsState(initial = emptyList())

    val isFavorites = playlist.name == Repository.FAVORITES_NAME

    Column(
        modifier = Modifier
            .width(120.dp)
            .clickable { onClick() },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.size(120.dp)
            ) {
                CollageCover(songs = songs, seed = playlist.id, size = 120.dp)
            }
            if (isFavorites) {
                Surface(
                    shape = androidx.compose.foundation.shape.CircleShape,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp)
                        .size(28.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Filled.Favorite,
                            contentDescription = null,
                            tint = androidx.compose.ui.graphics.Color.Black,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            playlist.name,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            tf("songs_count", songs.size),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun EmptyStateCard(onAddMusic: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                Icons.Filled.MusicNote,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(48.dp)
            )
            Spacer(Modifier.height(12.dp))
            Text(t("empty_library"), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                t("empty_library_hint"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(12.dp))
            Button(onClick = onAddMusic) {
                Icon(Icons.Filled.Folder, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text(t("select_folder"))
            }
        }
    }
}

@Composable
private fun NewPlaylistDialog(
    presetSongIds: List<Long>,
    onCreate: (String, String?) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var name by remember { mutableStateOf("") }
    var folderUri by remember { mutableStateOf<String?>(null) }
    var folderName by remember { mutableStateOf<String?>(null) }

    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            folderUri = uri.toString()
            folderName = try {
                DocumentFile.fromTreeUri(context, uri)?.name
            } catch (e: Exception) {
                null
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (presetSongIds.isEmpty()) {
                    t("new_list")
                } else {
                    tf("create_with_count", presetSongIds.size)
                }
            )
        },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(t("name")) },
                    singleLine = true
                )
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = { folderPicker.launch(null) }) {
                    Icon(Icons.Filled.Folder, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(folderUri?.let { t("change_folder") } ?: t("choose_folder"))
                }
                folderName?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (name.isNotBlank()) onCreate(name.trim(), folderUri) },
                enabled = name.isNotBlank()
            ) { Text(t("create")) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(t("cancel")) }
        }
    )
}

@Composable
private fun AddToPlaylistDialog(
    playlists: List<Playlist>,
    onPick: (Long) -> Unit,
    onNewList: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(t("add_to_list")) },
        text = {
            Column {
                if (playlists.isEmpty()) {
                    Text(
                        t("no_lists_create_first"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    playlists.forEach { pl ->
                        Text(
                            pl.name,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onPick(pl.id) }
                                .padding(vertical = 10.dp)
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = onNewList) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(t("new_list"))
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(t("cancel")) }
        }
    )
}

@Composable
private fun EditSongInfoDialog(
    song: Song,
    onSave: (String?, String?, String?) -> Unit,
    onDismiss: () -> Unit
) {
    var title by remember { mutableStateOf(song.title ?: "") }
    var artist by remember { mutableStateOf(song.artist ?: "") }
    var album by remember { mutableStateOf(song.album ?: "") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(t("edit_info_title")) },
        text = {
            Column {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text(t("title")) },
                    singleLine = true
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = artist,
                    onValueChange = { artist = it },
                    label = { Text(t("artist")) },
                    singleLine = true
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = album,
                    onValueChange = { album = it },
                    label = { Text(t("album")) },
                    singleLine = true
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(
                    title.trim().ifBlank { null },
                    artist.trim().ifBlank { null },
                    album.trim().ifBlank { null }
                )
            }) { Text(t("save")) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(t("cancel")) }
        }
    )
}
