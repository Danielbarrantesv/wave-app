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
import androidx.compose.ui.platform.LocalDensity
import coil.request.ImageRequest
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
 * Album art with graceful MusicNote fallback.
 * CRITICAL FIX: if the cached JPG (artUri) was deleted (cache wiped on
 * uninstall or after long idle), recover the cover from the audio file's
 * ID3 via AlbumArtCache.decode(filePath).
 */
@Composable
fun AlbumArt(song: Song?, size: Dp, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val sizePx = with(LocalDensity.current) { size.roundToPx().coerceAtLeast(64) }
    val model: Any? = remember(song?.id, song?.artUri, sizePx) {
        val raw: Any? = when {
            song == null -> null
            song.artUri.isNullOrBlank() -> null
            song.artUri!!.startsWith("content") -> Uri.parse(song.artUri)
            song.artUri!!.startsWith("/") -> File(song.artUri!!)
            else -> song.artUri
        }
        raw?.let { ImageRequest.Builder(context).data(it).size(sizePx).build() }
    }
    var coilFailed by remember(song?.id, song?.artUri) { mutableStateOf(false) }
    var recoveredBitmap by remember(song?.id, song?.artUri) {
        val fp = song?.filePath
        val cached = if (song != null && song.artUri.isNullOrBlank() && !fp.isNullOrBlank()) AlbumArtCache.peek(fp, 256) else null
        mutableStateOf(cached)
    }
    LaunchedEffect(song?.id, song?.artUri, coilFailed) {
        val s = song ?: return@LaunchedEffect
        if (recoveredBitmap != null) return@LaunchedEffect
        if ((model == null || coilFailed) && s.filePath.isNotBlank()) {
            val decoded = withContext(AlbumArtCache.dispatcher) { try { AlbumArtCache.decode(context, s.filePath, 256) } catch (_: Exception) { null } }
            if (decoded != null) recoveredBitmap = decoded
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
            bmp != null -> {
                Image(
                    bitmap = remember(bmp) { bmp.asImageBitmap() },
                    contentDescription = song?.displayTitle ?: "cover",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(size)
                )
            }
            model != null -> {
                AsyncImage(
                    model = model,
                    onError = { coilFailed = true },
                    contentDescription = song?.displayTitle ?: "cover",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(size)
                )
            }
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
