package com.mp3tify.ui.screens.queue
import com.mp3tify.data.model.displayArtist
import com.mp3tify.data.model.displayTitle

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mp3tify.playback.PlaybackController
import com.mp3tify.ui.components.AlbumArt
import com.mp3tify.ui.components.ScrollbarLazyColumn
import com.mp3tify.ui.t

@Composable
fun QueueScreen(onBack: () -> Unit) {
    val state by PlaybackController.state.collectAsState()

    Column(Modifier.fillMaxSize()) {
        // Fixed top row: back + queue icon + title + count.
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Filled.ArrowBack, contentDescription = t("back"))
            }
            Icon(
                Icons.Filled.QueueMusic,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "${t("queue")} (${state.playlist.size})",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
        }

        if (state.playlist.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    t("nothing_playing"),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            ScrollbarLazyColumn(modifier = Modifier.fillMaxSize()) {
                itemsIndexed(state.playlist) { index, song ->
                    val isCurrent = index == state.currentWindow
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { PlaybackController.seekToWindow(index) }
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        AlbumArt(song = song, size = 40.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                song.displayTitle,
                                style = if (isCurrent) {
                                    MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold)
                                } else {
                                    MaterialTheme.typography.bodyLarge
                                },
                                color = if (isCurrent) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                song.displayArtist,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        if (isCurrent) {
                            Icon(
                                Icons.Filled.DragHandle,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
        }
    }
}
