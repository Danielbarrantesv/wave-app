package com.mp3tify.ui.components

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mp3tify.data.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs

/** Deterministic PRNG (mulberry32) so a given seed always yields the same picks. */
fun mulberry32(seed: Long): () -> Int {
    var a = seed
    return {
        a += 0x6D2B79F5
        var t = a
        t = (t xor (t ushr 15)) * (t or 1)
        t = (t xor (t ushr 7)) * (t or 61)
        ((t xor (t ushr 14)) and 0x7FFFFFFFL).toInt()
    }
}

/** 2x2 collage cover built from (up to) 4 songs picked with a seeded mulberry32. */
@Composable
fun CollageCover(
    songs: List<Song>,
    seed: Long,
    size: Dp,
    modifier: Modifier = Modifier
) {
    val picks = remember(seed, songs.size) {
        if (songs.isEmpty()) {
            emptyList()
        } else {
            val rnd = mulberry32(if (seed != 0L) seed else 1L)
            val indices = LinkedHashSet<Int>()
            val wanted = songs.size.coerceAtMost(4)
            var safety = 0
            while (indices.size < wanted && safety < 64) {
                indices.add(abs(rnd()) % songs.size)
                safety++
            }
            if (indices.isEmpty()) indices.add(0)
            indices.map { songs[it] }
        }
    }

    Box(modifier = modifier.size(size)) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.weight(1f)) {
                CollageTile(picks.getOrNull(0), Modifier.weight(1f))
                CollageTile(picks.getOrNull(1), Modifier.weight(1f))
            }
            Row(Modifier.weight(1f)) {
                CollageTile(picks.getOrNull(2), Modifier.weight(1f))
                CollageTile(picks.getOrNull(3), Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun CollageTile(song: Song?, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var bitmap by remember(song?.id) { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(song?.id) {
        val s = song ?: return@LaunchedEffect
        // CRITICAL FIX (Bug portadas restauradas): prefer artUri (JPG cache file)
        // and pass filePath as fallback. If the JPG cache was wiped on uninstall,
        // AlbumArtCache.decode falls back to extracting the ID3 picture from the
        // audio content:// URI via filePath.
        val path = s.artUri?.takeIf { it.isNotBlank() } ?: s.filePath
        val fallback = if (path != s.filePath) s.filePath else null
        if (path.isNotBlank()) {
            bitmap = withContext(Dispatchers.IO) {
                try { AlbumArtCache.decode(context, path, 256, fallback) } catch (e: Exception) { null }
            }
        }
    }

    val bmp = bitmap
    if (bmp != null) {
        Image(
            bitmap = bmp.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier.fillMaxSize()
        )
    } else {
        // Gradient placeholder with a stable hue derived from the title hash.
        val hue = abs((song?.title ?: "mp3tify").hashCode()) % 360
        val base = Color.hsv(hue.toFloat(), 0.55f, 0.40f)
        val darker = Color.hsv(((hue + 40) % 360).toFloat(), 0.60f, 0.25f)
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(Brush.linearGradient(listOf(base, darker)))
        )
    }
}
