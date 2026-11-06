package com.mp3tify.ui.components
import com.mp3tify.data.model.displayArtist
import com.mp3tify.data.model.displayTitle

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mp3tify.playback.PlaybackController
import com.mp3tify.playback.PlaybackState
import com.mp3tify.ui.t

/** Mini player bar: cover + title/artist + prev/play/next + thin progress line. */
@Composable
fun PlayerBar(
    state: PlaybackState,
    onOpenNowPlaying: () -> Unit
) {
    val current = state.playlist.getOrNull(state.currentWindow)
    AnimatedVisibility(visible = current != null) {
        // CRITICAL #1: navigationBarsPadding() empuja el PlayerBar por encima de la
        // gestural nav bar. Sin esto, en modelos con nav bar de 3 botones o gesture
        // pill, los botones prev/play/next quedan tapados por la nav bar del sistema.
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.navigationBarsPadding()
        ) {
            Column {
                LinearProgressIndicator(
                    progress = {
                        if (state.durationMs > 0) {
                            (state.positionMs.toFloat() / state.durationMs).coerceIn(0f, 1f)
                        } else {
                            0f
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.outline
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenNowPlaying() }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AlbumArt(song = current, size = 40.dp)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            current?.displayTitle ?: "",
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            current?.displayArtist ?: "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    IconButton(onClick = { PlaybackController.skipToPrevious() }) {
                        Icon(Icons.Filled.SkipPrevious, contentDescription = t("previous"))
                    }
                    IconButton(onClick = {
                        if (state.isPlaying) PlaybackController.pause() else PlaybackController.play()
                    }) {
                        Icon(
                            if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = t("pause"),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                    IconButton(onClick = { PlaybackController.skipToNext() }) {
                        Icon(Icons.Filled.SkipNext, contentDescription = t("next"))
                    }
                }
            }
        }
    }
}
