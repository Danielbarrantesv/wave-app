package com.mp3tify.ui.components

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.mp3tify.data.model.Song
import com.mp3tify.data.model.displayAlbum
import com.mp3tify.data.model.displayArtist
import com.mp3tify.data.model.displayTitle
import java.io.File

/** Album art with graceful MusicNote fallback. Supports content:// uris and local files. */
@Composable
fun AlbumArt(song: Song?, size: Dp, modifier: Modifier = Modifier) {
    val model: Any? = when {
        song == null -> null
        song.artUri.isNullOrBlank() -> null
        song.artUri!!.startsWith("content") -> Uri.parse(song.artUri)
        song.artUri!!.startsWith("/") -> File(song.artUri!!)
        else -> song.artUri
    }
    Box(
        modifier = modifier
            .size(size)
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        if (model != null) {
            AsyncImage(
                model = model,
                contentDescription = song?.displayTitle ?: "cover",
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(size)
            )
        } else {
            Icon(
                imageVector = Icons.Filled.MusicNote,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(size / 2)
            )
        }
    }
}
