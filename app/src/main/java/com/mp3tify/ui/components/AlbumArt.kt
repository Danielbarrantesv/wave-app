package com.mp3tify.ui.components

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.mp3tify.data.model.Song
import com.mp3tify.data.model.displayAlbum
import com.mp3tify.data.model.displayArtist
import com.mp3tify.data.model.displayTitle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Album art with graceful MusicNote fallback. Supports content:// uris and local files.
 *
 * CRITICAL FIX (Bug portadas restauradas): if the cached JPG (artUri) was
 * deleted on uninstall, fall back to extracting the ID3 picture from the audio
 * content:// URI via AlbumArtCache.decode(filePath). This makes the cover
 * reappear after a backup-restore even when the cache was wiped.
 */
@Composable
fun AlbumArt(song: Song?, size: Dp, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var recoveredBitmap by remember(song?.id) { mutableStateOf<Bitmap?>(null) }

    // Coil model from the cached artUri (fast path when the JPG exists).
    val model: Any? = when {
        song == null -> null
        song.artUri.isNullOrBlank() -> null
        song.artUri!!.startsWith("content") -> Uri.parse(song.artUri)
        song.artUri!!.startsWith("/") -> File(song.artUri!!)
        else -> song.artUri
    }

    // CRITICAL FIX: if artUri's file is missing (cache wiped on uninstall),
    // try to recover the cover from the audio file's ID3 via AlbumArtCache.
    LaunchedEffect(song?.id) {
        val s = song ?: return@LaunchedEffect
        val artPath = s.artUri?.takeIf { it.isNotBlank() }
        // Only attempt recovery if artUri points to a file that doesn't exist
        // (or is null/blank) AND we have a filePath to fall back to.
        if (artPath != null && !artPath.startsWith("content://") && !File(artPath).exists()) {
            recoveredBitmap = withContext(Dispatchers.IO) {
                try { AlbumArtCache.decode(context, s.filePath, 256) } catch (e: Exception) { null }
            }
        } else if (artPath.isNullOrBlank() && s.filePath.isNotBlank()) {
            recoveredBitmap = withContext(Dispatchers.IO) {
                try { AlbumArtCache.decode(context, s.filePath, 256) } catch (e: Exception) { null }
            }
        }
    }

    Box(
        modifier = modifier
            .size(size)
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        val bmp = recoveredBitmap
        when {
            // Recovered bitmap from ID3 fallback (cache was wiped).
            bmp != null -> {
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = song?.displayTitle ?: "cover",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(size)
                )
            }
            // Fast path: cached JPG exists, use Coil AsyncImage.
            model != null -> {
                AsyncImage(
                    model = model,
                    contentDescription = song?.displayTitle ?: "cover",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(size)
                )
            }
            // No cover at all — show MusicNote placeholder.
            else -> {
                Icon(
                    imageVector = Icons.Filled.MusicNote,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(size / 2)
                )
            }
        }
    }
}
