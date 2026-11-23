package com.mp3tify.ui.components

import android.app.Activity
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.mp3tify.data.model.Song
import com.mp3tify.data.model.displayArtist
import com.mp3tify.data.model.displayTitle
import com.mp3tify.data.repository.DeleteFileResult
import com.mp3tify.data.repository.MoveFileResult
import com.mp3tify.data.repository.SongFileOps
import com.mp3tify.ui.I18n
import com.mp3tify.ui.t
import kotlinx.coroutines.launch

@Composable
fun SongOptionsDialog(
    song: Song,
    onToggleFavorite: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onEditInfo: () -> Unit,
    onMoveToFolder: () -> Unit,
    onDelete: () -> Unit,
    onDeletePermanently: () -> Unit,
    onSelect: (() -> Unit)? = null,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 6.dp) {
            Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    AlbumArt(song = song, size = 44.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(song.displayTitle, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(song.displayArtist, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Box(Modifier.fillMaxWidth().padding(vertical = 4.dp).height(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
                OptionRow(if (song.isFavorite) Icons.Filled.FavoriteBorder else Icons.Filled.Favorite, if (song.isFavorite) t("remove_from_favorites") else t("add_to_favorites")) { onDismiss(); onToggleFavorite() }
                OptionRow(Icons.Filled.PlaylistAdd, t("add_to_list")) { onDismiss(); onAddToPlaylist() }
                OptionRow(Icons.Filled.Edit, t("edit_info")) { onDismiss(); onEditInfo() }
                OptionRow(Icons.Filled.Folder, t("move_to_folder")) { onDismiss(); onMoveToFolder() }
                OptionRow(Icons.Filled.Delete, t("delete_song")) { onDismiss(); onDelete() }
                OptionRow(Icons.Filled.DeleteForever, t("delete_permanently"), tint = MaterialTheme.colorScheme.error) { onDismiss(); onDeletePermanently() }
                if (onSelect != null) OptionRow(Icons.Filled.CheckBox, t("select_songs")) { onDismiss(); onSelect() }
            }
        }
    }
}

@Composable
private fun OptionRow(icon: ImageVector, label: String, tint: Color = MaterialTheme.colorScheme.onSurface, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(16.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, color = tint)
    }
}

@Composable
fun SongFileActionsHost(
    songForOptions: Song?,
    onDismissOptions: () -> Unit,
    onToggleFavorite: (Song) -> Unit,
    onAddToPlaylist: (Song) -> Unit,
    onEditInfo: (Song) -> Unit,
    onDeleteFromApp: (Song) -> Unit,
    onSelect: ((Song) -> Unit)? = null,
    snackbarHostState: SnackbarHostState
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var songToDeletePerm by remember { mutableStateOf<Song?>(null) }
    var pendingMoveSong by remember { mutableStateOf<Song?>(null) }
    var pendingConsentDelete by remember { mutableStateOf<Song?>(null) }

    val consentLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
        val delSong = pendingConsentDelete
        pendingConsentDelete = null
        if (res.resultCode == Activity.RESULT_OK && delSong != null) {
            scope.launch { SongFileOps.finishPermanentDelete(context, delSong); snackbarHostState.showSnackbar(I18n.t("deleted_permanently_ok")) }
        }
    }

    val moveFolderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        val song = pendingMoveSong
        pendingMoveSong = null
        if (uri != null && song != null) {
            try { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) } catch (_: Exception) {}
            scope.launch {
                when (val r = SongFileOps.moveSongToFolder(context, song, uri)) {
                    is MoveFileResult.Moved -> { snackbarHostState.showSnackbar(I18n.t("moved_to_folder_ok")); r.deleteSourceConsent?.let { consentLauncher.launch(IntentSenderRequest.Builder(it).build()) } }
                    is MoveFileResult.Failed -> snackbarHostState.showSnackbar(I18n.t("move_failed"))
                }
            }
        }
    }

    songForOptions?.let { s ->
        SongOptionsDialog(
            song = s,
            onToggleFavorite = { onToggleFavorite(s) },
            onAddToPlaylist = { onAddToPlaylist(s) },
            onEditInfo = { onEditInfo(s) },
            onMoveToFolder = { pendingMoveSong = s; moveFolderLauncher.launch(null) },
            onDelete = { onDeleteFromApp(s) },
            onDeletePermanently = { songToDeletePerm = s },
            onSelect = if (onSelect != null) { { onSelect(s) } } else null,
            onDismiss = onDismissOptions
        )
    }

    songToDeletePerm?.let { s ->
        AlertDialog(
            onDismissRequest = { songToDeletePerm = null },
            title = { Text(t("delete_permanently"), color = MaterialTheme.colorScheme.error) },
            text = { Text("${s.displayTitle}\n\n${t("delete_permanently_confirm")}") },
            confirmButton = {
                TextButton(onClick = {
                    val target = s; songToDeletePerm = null
                    scope.launch {
                        when (val r = SongFileOps.deleteSongPermanently(context, target)) {
                            is DeleteFileResult.Deleted -> snackbarHostState.showSnackbar(I18n.t("deleted_permanently_ok"))
                            is DeleteFileResult.NeedsConsent -> { pendingConsentDelete = target; consentLauncher.launch(IntentSenderRequest.Builder(r.intentSender).build()) }
                            is DeleteFileResult.Failed -> snackbarHostState.showSnackbar(I18n.t("delete_failed"))
                        }
                    }
                }) { Text(t("delete_permanently"), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { songToDeletePerm = null }) { Text(t("cancel")) } }
        )
    }
}
