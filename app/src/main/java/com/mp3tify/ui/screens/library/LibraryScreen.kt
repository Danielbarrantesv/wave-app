package com.mp3tify.ui.screens.library

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveEta
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.QueueMusic
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
import androidx.compose.runtime.LaunchedEffect
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
import com.mp3tify.data.model.displayArtist
import com.mp3tify.data.model.displayTitle
import com.mp3tify.data.repository.Repository
import com.mp3tify.playback.PlaybackController
import com.mp3tify.ui.components.AlbumArt
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
    val artists by vm.artists.collectAsState()
    val recentlyPlayed by vm.recentlyPlayed.collectAsState()
    val selectionMode by vm.selectionMode.collectAsState()
    val selectedIds by vm.selectedIds.collectAsState()
    val importing by vm.importing.collectAsState()
    val importProgress by vm.importProgress.collectAsState()
    val sortOrder by vm.sortOrder.collectAsState()

    // CRITICAL (v2.5.13 UI): canción actualmente sonando + IDs de playlists que la
    // contienen. Se recalcula cuando cambia el currentSong (LaunchedEffect corre en
    // IO via Repository.playlistIdsForSong, query SQL simple).
    val currentSong = playbackState.playlist.getOrNull(playbackState.currentWindow)
    var playingPlaylistIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    val repo = remember { Repository.get(context) }
    LaunchedEffect(currentSong?.id) {
        playingPlaylistIds = if (currentSong != null) {
            try { repo.playlistIdsForSong(currentSong.id).toSet() } catch (e: Exception) { emptySet() }
        } else {
            emptySet()
        }
    }

    // CRITICAL (v2.5.13 UI): Favoritos SIEMPRE primero, luego el resto por
    // updatedAt DESC (orden original del DAO). Sin este sort, si el usuario crea
    // o edita una playlist, Favoritos baja de la primera posición porque su
    // updatedAt es viejo.
    val sortedPlaylists = remember(playlists) {
        playlists.sortedWith(
            compareByDescending<Playlist> { it.name == Repository.FAVORITES_NAME }
                .thenByDescending { it.updatedAt }
        )
    }

    var showNewPlaylistDialog by remember { mutableStateOf(false) }
    var showAddToPlaylistDialog by remember { mutableStateOf(false) }
    var songForAddToPlaylist by remember { mutableStateOf<Song?>(null) }
    var songToEdit by remember { mutableStateOf<Song?>(null) }
    var songToDelete by remember { mutableStateOf<Song?>(null) }
    var sortMenuOpen by remember { mutableStateOf(false) }
    // #1 Limpiar pagina principal: la lista completa de canciones esta colapsada
    // por defecto. Tap en el header la expande/colapsa.
    var libraryExpanded by remember { mutableStateOf(false) }

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

    var showAddMusicDialog by remember { mutableStateOf(false) }
    // #1 Subfolder playlist dialog state — declarar ANTES del pickFolderLauncher
    // porque el callback del launcher usa estas variables.
    var showSubfolderDialog by remember { mutableStateOf(false) }
    var pendingFolderUri by remember { mutableStateOf("") }
    var pendingSubfolders by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }

    val pickFolderLauncher = rememberLauncherForActivityResult(
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
            // #1 Subfolder playlist dialog: escanear subcarpetas antes de importar.
            // Si hay subcarpetas con audio, preguntar si quiere crear una playlist
            // por cada una. Si no hay subcarpetas o el usuario dice que no, importacion
            // normal (todo a la biblioteca).
            scope.launch {
                val subfolders = vm.findSubfoldersWithAudio(uri.toString())
                if (subfolders.isNotEmpty()) {
                    pendingFolderUri = uri.toString()
                    pendingSubfolders = subfolders
                    showSubfolderDialog = true
                } else {
                    vm.importFromFolder(uri.toString()) { result -> onImportDone(result) }
                }
            }
        }
    }

    val pickFilesLauncher = rememberLauncherForActivityResult(
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
                // STICKY top bar (Scaffold pins it above the scrolling content).
                // CRITICAL #1: windowInsets explicitos para garantizar que el TopAppBar
                // NO se superponga con la status bar (bateria/hora/wifi) en ningun modelo.
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
                    ),
                    windowInsets = WindowInsets.statusBars
                )
            },
            bottomBar = {
                PlayerBar(state = playbackState, onOpenNowPlaying = onOpenNowPlaying)
            },
            floatingActionButton = {
                ExtendedFloatingActionButton(
                    onClick = { showAddMusicDialog = true },
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    // CRITICAL (v2.5.13 UI): texto compacto "+ Música" en lugar de
                    // "Agregar música" para que el FAB ocupe menos espacio en
                    // pantallas pequeñas y sea más rápido de leer.
                    text = { Text(t("add_music_short")) },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = Color.Black
                )
            }
        ) { padding ->
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                // SelectionBanner fijo arriba (NO scrollea con la lista)
                if (selectionMode) {
                    SelectionBanner(
                        count = selectedIds.size,
                        onCreateList = { showNewPlaylistDialog = true },
                        onAddTo = { showAddToPlaylistDialog = true },
                        onAddToFavorites = {
                            scope.launch {
                                selectedIds.toList().forEach { id ->
                                    try {
                                        val song = songs.firstOrNull { it.id == id }
                                        if (song != null && !song.isFavorite) vm.toggleFavorite(song)
                                    } catch (e: Exception) { e.printStackTrace() }
                                }
                                vm.exitSelectionMode()
                            }
                        },
                        onDelete = {
                            selectedIds.toList().forEach { id ->
                                val song = songs.firstOrNull { it.id == id }
                                if (song != null) vm.deleteSong(song)
                            }
                            vm.exitSelectionMode()
                        },
                        onExit = { vm.exitSelectionMode() }
                    )
                }

                ScrollbarLazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 96.dp)
                ) {
                item {
                    // #2 Header horizontal: portada IZQ + titulo + count DER + chevron
                    // #1 Tap expande/colapsa la lista completa de canciones para limpiar la home.
                    LibraryHeaderRow(
                        songs = songs,
                        expanded = libraryExpanded,
                        onToggleExpanded = { libraryExpanded = !libraryExpanded },
                        onPlayAll = { vm.playAll(false) },
                        onShuffleAll = { vm.playAll(true) },
                        onSortClick = { sortMenuOpen = true },
                        sortLabel = sortLabel(sortOrder)
                    )
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

                // ===== Sección Escuchadas recientemente (#4) =====
                // Solo se muestra si hay al menos una canción reproducida (lastPlayedAt > 0).
                // Slider horizontal con las últimas 20 canciones reproducidas.
                if (recentlyPlayed.isNotEmpty()) {
                    item {
                        Text(
                            t("recently_played_section"),
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }
                    item {
                        LazyRow(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 4.dp),
                            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp)
                        ) {
                            items(recentlyPlayed, key = { "rp_${it.id}" }) { song ->
                                RecentlyPlayedCard(
                                    song = song,
                                    onClick = {
                                        vm.play(song)
                                        onOpenNowPlaying()
                                    }
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
                        Text(
                            t("your_lists"),
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f)
                        )
                        // Botón "+" con texto "Crear lista" (#6)
                        TextButton(onClick = { showNewPlaylistDialog = true }) {
                            Icon(
                                Icons.Filled.Add,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(t("create_list"))
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
                    // CRITICAL (v2.5.14 UI): el usuario pidió que las listas salgan
                    // como un slider horizontal (carrusel deslizable), no como grid
                    // vertical de 2 columnas. Volvemos a LazyRow — cada PlaylistCard
                    // se muestra una al lado de la otra, scroll horizontal para ver
                    // más. Favoritos queda primero (sortedPlaylists) y el marcado de
                    // playlist sonando (isPlaying) se mantiene.
                    item {
                        LazyRow(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp)
                        ) {
                            items(sortedPlaylists) { pl ->
                                PlaylistCard(
                                    playlist = pl,
                                    isPlaying = playingPlaylistIds.contains(pl.id),
                                    onClick = { onOpenPlaylist(pl.id) }
                                )
                            }
                        }
                    }
                }

                // ===== Sección Artistas (#4) =====
                // Solo se muestra si hay al menos un artista con MÁS de una canción.
                // LasArtistCards son virtuales: se generan a partir de las canciones
                // existentes (no se persisten en la base de datos como playlists).
                val artistsWithMultiple = artists.filter { it.songs.size > 1 }
                if (artistsWithMultiple.isNotEmpty()) {
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                t("artists_section"),
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                tf("artists_count", artistsWithMultiple.size),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    item {
                        LazyRow(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 4.dp),
                            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp)
                        ) {
                            items(artistsWithMultiple, key = { "art_${it.name}" }) { artist ->
                                ArtistCard(
                                    artist = artist,
                                    onClick = {
                                        // Tap: reproduce todas las canciones del artista
                                        vm.playArtist(artist, shuffle = false)
                                        onOpenNowPlaying()
                                    },
                                    onShuffleClick = {
                                        // Botón de shuffle dedicado en la card
                                        vm.playArtist(artist, shuffle = true)
                                        onOpenNowPlaying()
                                    }
                                )
                            }
                        }
                    }
                }

                // #1 La lista completa de canciones solo se muestra si el usuario
                // expandió la biblioteca (tap en el header). Por defecto colapsada
                // para limpiar la home: solo header + escuchadas recientemente +
                // tus listas + artistas.
                if (libraryExpanded) {
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
                } // closes if (libraryExpanded)
            }
                } // closes Column added for fixed SelectionBanner
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
    // #1 Subfolder playlist dialog: se muestra cuando el usuario selecciona una
    // carpeta con subcarpetas que contienen audio. Pregunta si quiere crear una
    // playlist por cada subcarpeta (con el nombre de la carpeta capitalizado).
    if (showSubfolderDialog) {
        AlertDialog(
            onDismissRequest = {
                // Si cierra sin responder, importar normal (sin subplaylists)
                showSubfolderDialog = false
                if (pendingFolderUri.isNotBlank()) {
                    vm.importFromFolder(pendingFolderUri) { result -> onImportDone(result) }
                }
                pendingFolderUri = ""
                pendingSubfolders = emptyList()
            },
            title = { Text(t("subfolder_dialog_title")) },
            text = {
                Column {
                    Text(tf("subfolder_dialog_message", pendingSubfolders.size))
                    Spacer(Modifier.height(12.dp))
                    // Mostrar nombres de las subcarpetas que se van a convertir en playlists
                    pendingSubfolders.forEach { (name, _) ->
                        Text(
                            "• ${name.replaceFirstChar { it.uppercase() }}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showSubfolderDialog = false
                    val subs = pendingSubfolders
                    val folderUri = pendingFolderUri
                    pendingFolderUri = ""
                    pendingSubfolders = emptyList()
                    // Importar creando playlist por subcarpeta
                    vm.importFolderWithSubPlaylists(folderUri, subs) { total ->
                        scope.launch {
                            try {
                                val msg = if (total > 0) {
                                    I18n.tf("added_songs", total) + " + ${subs.size} listas"
                                } else {
                                    I18n.t("no_music_found")
                                }
                                snackbarHostState.showSnackbar(msg)
                            } catch (e: Exception) { e.printStackTrace() }
                        }
                    }
                }) { Text(t("subfolder_dialog_yes")) }
            },
            dismissButton = {
                TextButton(onClick = {
                    showSubfolderDialog = false
                    val folderUri = pendingFolderUri
                    pendingFolderUri = ""
                    pendingSubfolders = emptyList()
                    // Importar normal (sin subplaylists)
                    vm.importFromFolder(folderUri) { result -> onImportDone(result) }
                }) { Text(t("subfolder_dialog_no")) }
            }
        )
    }

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
    onAddToFavorites: () -> Unit,
    onDelete: () -> Unit,
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
            // Exit selection mode
            IconButton(onClick = onExit) {
                Icon(Icons.Filled.Close, contentDescription = t("cancel"))
            }
            // Count text (takes remaining space)
            Text(
                tf("selected_count", count),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                maxLines = 1
            )
            // Add to favorites (heart icon, accent tint)
            IconButton(onClick = onAddToFavorites) {
                Icon(
                    Icons.Filled.Favorite,
                    contentDescription = t("add_to_favorites"),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            // Add to existing playlist (PlaylistAdd icon)
            IconButton(onClick = onAddTo) {
                Icon(
                    Icons.Filled.PlaylistAdd,
                    contentDescription = t("add_to_list")
                )
            }
            // Create new playlist with selected (QueueMusic icon)
            IconButton(onClick = onCreateList) {
                Icon(
                    Icons.Filled.QueueMusic,
                    contentDescription = tf("create_with_count", count)
                )
            }
            // Delete selected (Delete icon, error tint)
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = t("delete_song"),
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

/**
 * #2 LibraryHeaderRow — header horizontal de la biblioteca.
 *
 * Layout:
 *   [CollageCover 72dp IZQ] | [Tu biblioteca + count + sort chip + Play/Shuffle DER] | [Chevron]
 *
 * Tap en toda la fila expande/colapsa la lista completa de canciones (#1).
 * Sort chip abre el menú de orden (5 opciones: Recently Added, Recently Played,
 * Title, Artist, Duration).
 */
@Composable
private fun LibraryHeaderRow(
    songs: List<Song>,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onPlayAll: () -> Unit,
    onShuffleAll: () -> Unit,
    onSortClick: () -> Unit,
    sortLabel: String
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .clickable { onToggleExpanded() }
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Portada collage IZQ (72dp)
            CollageCover(songs = songs, seed = 42L, size = 72.dp)

            Spacer(Modifier.width(12.dp))

            // Centro: titulo + count + sort chip + botones play/shuffle
            Column(Modifier.weight(1f)) {
                Text(
                    t("your_library"),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    tf("songs_count", songs.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Sort chip (tap abre menú)
                    Box {
                        TextButton(
                            onClick = onSortClick,
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                        ) {
                            Icon(
                                Icons.Filled.Sort,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                sortLabel,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1
                            )
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    // Play all (icon button)
                    IconButton(
                        onClick = onPlayAll,
                        enabled = songs.isNotEmpty(),
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            Icons.Filled.PlayArrow,
                            contentDescription = t("play_all"),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                    // Shuffle (icon button)
                    IconButton(
                        onClick = onShuffleAll,
                        enabled = songs.isNotEmpty(),
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            Icons.Filled.Shuffle,
                            contentDescription = t("shuffle"),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            // Chevron DER (indica expandible)
            Icon(
                if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * RecentlyPlayedCard — tarjeta horizontal compacta para "Escuchadas recientemente".
 * Muestra portada 56dp + titulo + artista. Tap reproduce.
 */
@Composable
private fun RecentlyPlayedCard(
    song: Song,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .width(112.dp)
            .clickable { onClick() },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        AlbumArt(song = song, size = 112.dp)
        Spacer(Modifier.height(4.dp))
        Text(
            song.displayTitle,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            song.displayArtist,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
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
    onClick: () -> Unit,
    // CRITICAL (v2.5.13 UI): true si esta playlist contiene la canción actualmente
    // sonando. Aplica borde 2px en la portada + ▷ delante del nombre + nombre en
    // color primario para indicar visualmente qué playlist(s) está(n) sonando.
    isPlaying: Boolean = false
) {
    val context = LocalContext.current
    val playlistRepo = remember { Repository.get(context) }
    val songs by remember(playlist.id) {
        playlistRepo.observePlaylistSongs(playlist.id)
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
                // CRITICAL (v2.5.13 UI): borde de 2dp del color primario cuando esta
                // playlist está sonando. Aplica al Surface que contiene la portada
                // (favoritos con corazón, o collage de discos).
                modifier = Modifier
                    .size(120.dp)
                    .then(
                        if (isPlaying) Modifier.border(
                            width = 2.dp,
                            color = MaterialTheme.colorScheme.primary,
                            shape = RoundedCornerShape(12.dp)
                        ) else Modifier
                    )
            ) {
                if (isFavorites) {
                    // CRITICAL (#5 Favoritos): la portada de Favoritos es un corazón
                    // gigante del tamaño del cuadro, no el collage de discos.
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Filled.Favorite,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(72.dp)
                        )
                    }
                } else {
                    CollageCover(songs = songs, seed = playlist.id, size = 120.dp)
                }
            }
            // Pequeño badge corazón en esquina solo si NO es Favoritos (el corazón
            // gigante ya lo identifica; el badge sería redundante).
            // Favoritos ya no lleva el badge circular pequeño porque la portada entera
            // es un corazón.
        }
        Spacer(Modifier.height(6.dp))
        // CRITICAL (v2.5.13 UI): si isPlaying, anteponer ▷ al nombre y pintar el
        // texto en color primario. El ▷ es U+25B7 (geometric shape WHITE RIGHT-
        // POINTING TRIANGLE) — se renderiza bien en todas las fuentes.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isPlaying) {
                Text(
                    "\u25B7",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = androidx.compose.ui.unit.TextUnit(10f, androidx.compose.ui.unit.TextUnitType.Sp)
                )
                Spacer(Modifier.width(2.dp))
            }
            Text(
                playlist.name,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                color = if (isPlaying) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface,
                fontWeight = if (isPlaying) FontWeight.Bold else FontWeight.Normal
            )
        }
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
                // #3 Siempre empezar nombre de playlist en mayuscula.
                onClick = {
                    if (name.isNotBlank()) {
                        val trimmed = name.trim()
                        // Capitalizar primera letra, mantener resto tal cual.
                        val capitalizedName = if (trimmed.isNotEmpty()) {
                            trimmed.substring(0, 1).uppercase() + trimmed.substring(1)
                        } else trimmed
                        onCreate(capitalizedName, folderUri)
                    }
                },
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

/**
 * ArtistCard — tarjeta horizontal para la sección "Artistas" (#4).
 *
 * Mostrar: portada (usa la primera canción con artUri, o fallback icono Person),
 * nombre del artista, conteo de canciones, y un botón de shuffle overlay.
 *
 * Tap en la card: reproduce todas las canciones del artista en orden.
 * Tap en el botón shuffle (esquina inferior derecha): reproduce aleatorio.
 */
@Composable
private fun ArtistCard(
    artist: Repository.ArtistWithSongs,
    onClick: () -> Unit,
    onShuffleClick: () -> Unit
) {
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
                // Usar la primera canción con portada como imagen; si no, icono Person.
                val songWithArt = artist.songs.firstOrNull { !it.artUri.isNullOrBlank() }
                if (songWithArt != null) {
                    AlbumArt(song = songWithArt, size = 120.dp)
                } else {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Filled.Person,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(48.dp)
                        )
                    }
                }
            }
            // Botón shuffle overlay (esquina inferior derecha)
            Surface(
                shape = androidx.compose.foundation.shape.CircleShape,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(6.dp)
                    .size(32.dp)
                    .clickable { onShuffleClick() }
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Filled.Shuffle,
                        contentDescription = t("shuffle"),
                        tint = androidx.compose.ui.graphics.Color.Black,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            artist.name,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            tf("songs_count", artist.songs.size),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
    }
}
