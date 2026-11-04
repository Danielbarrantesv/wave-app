package com.mp3tify.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mp3tify.data.model.Song
import com.mp3tify.data.model.displayAlbum
import com.mp3tify.data.model.displayArtist
import com.mp3tify.data.model.displayTitle
import com.mp3tify.ui.t

/** Song list row: art + title/artist (+ favorite heart) + duration + ⋮ menu. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SongRow(
    song: Song,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {},
    onToggleFavorite: () -> Unit = {},
    onAddToPlaylist: () -> Unit = {},
    onEditInfo: () -> Unit = {},
    onDelete: () -> Unit = {}
) {
    var menuOpen by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AlbumArt(song = song, size = 48.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                song.displayTitle,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (song.isFavorite) {
                    Icon(
                        Icons.Filled.Favorite,
                        contentDescription = t("favorite"),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                }
                Text(
                    song.displayArtist,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Text(
            formatDuration(song.durationMs),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Box {
            IconButton(onClick = { menuOpen = true }) {
                Icon(Icons.Filled.MoreVert, contentDescription = t("more_options"))
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text(if (song.isFavorite) t("remove_from_favorites") else t("add_to_favorites")) },
                    onClick = {
                        menuOpen = false
                        onToggleFavorite()
                    },
                    leadingIcon = {
                        Icon(
                            if (song.isFavorite) Icons.Filled.FavoriteBorder else Icons.Filled.Favorite,
                            contentDescription = null
                        )
                    }
                )
                DropdownMenuItem(
                    text = { Text(t("add_to_list")) },
                    onClick = {
                        menuOpen = false
                        onAddToPlaylist()
                    },
                    leadingIcon = { Icon(Icons.Filled.PlaylistAdd, contentDescription = null) }
                )
                DropdownMenuItem(
                    text = { Text(t("edit_info")) },
                    onClick = {
                        menuOpen = false
                        onEditInfo()
                    },
                    leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) }
                )
                DropdownMenuItem(
                    text = { Text(t("delete_song")) },
                    onClick = {
                        menuOpen = false
                        onDelete()
                    },
                    leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) }
                )
            }
        }
    }
}

/** m:ss formatting used across the app. */
fun formatDuration(ms: Long): String {
    if (ms <= 0) return "0:00"
    val totalSec = ms / 1000
    val m = totalSec / 60
    val s = totalSec % 60
    return "$m:${s.toString().padStart(2, '0')}"
}
