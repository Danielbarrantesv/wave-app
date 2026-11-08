package com.mp3tify.ui.screens.lyrics

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.MusicOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.mp3tify.lyrics.LrcLibClient
import com.mp3tify.lyrics.LrcParser
import com.mp3tify.lyrics.ParsedLyrics
import com.mp3tify.playback.PlaybackController
import com.mp3tify.ui.t
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Lyrics screen — AUTO-SEARCH ON ENTRY. There are NO buttons at all:
 * the cache is checked first, otherwise lrclib.net is queried automatically.
 *
 * CRITICAL (v2.5.23 UI):
 *   - Background: portada del album actual como fondo (AsyncImage de Coil),
 *     con scrim oscuro (Color.Black alpha 0.75) encima para que la letra
 *     sea legible.
 *   - Top bar: padding top con WindowInsets.statusBars para que la flecha
 *     de back y el título "Letra" no se corten con la status bar.
 *   - Texto de la letra: blanco para línea activa, gris claro para inactivas,
 *     para contraste con el fondo oscuro.
 */
@Composable
fun LyricsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val state by PlaybackController.state.collectAsState()
    val current = state.playlist.getOrNull(state.currentWindow)

    var loading by remember { mutableStateOf(false) }
    var notAvailable by remember { mutableStateOf(false) }
    var parsed by remember { mutableStateOf<ParsedLyrics?>(null) }

    val listState = rememberLazyListState()

    LaunchedEffect(current?.id) {
        // CRITICAL (v2.5.27): REVERTIDO el listState.scrollToItem(0) de v2.5.25.
        // El usuario reportó que causaba el spinner 'buscando letra' pegado.
        // Probablemente scrollToItem(0) se cuelga cuando el LazyColumn aún no
        // tiene items renderizados (parsed == null al inicio del ciclo), y eso
        // bloquea el LaunchedEffect antes de que pueda setear loading o cargar
        // las letras. Sin esta línea, el flujo funciona correctamente.
        val song = current
        // CRITICAL (v2.5.26): resetear loading al inicio de cada ciclo. Sin esto,
        // si el LaunchedEffect se cancela a mitad (porque current?.id cambió
        // rapidamente, ej: skip varias veces), loading se queda en true para
        // siempre y el usuario ve el spinner 'buscando letra' pegado.
        loading = false
        parsed = null
        notAvailable = false
        if (song == null) {
            return@LaunchedEffect
        }
        val artist = song.artist ?: ""
        val track = song.title ?: song.fileName
        if (track.isBlank()) {
            notAvailable = true
            return@LaunchedEffect
        }
        // CRITICAL (v2.5.26): try-finally para asegurar que loading siempre se
        // resetee a false, incluso si la coroutine se cancela (CancellationException)
        // o si hay un error inesperado. Sin esto, loading puede quedar en true.
        try {
            if (LrcLibClient.isCached(context, artist, track)) {
                // Cache hit: cargar sincrónicamente (rápido, no necesita loading)
                val raw = LrcLibClient.getLyrics(
                    context, artist, track, song.album, song.durationMs / 1000
                )
                val p = raw?.let { LrcParser.parse(it) }
                if (p != null) parsed = p else notAvailable = true
            } else {
                // Cache miss: fetch de red con loading visible
                loading = true
                val raw = withContext(Dispatchers.IO) {
                    LrcLibClient.getLyrics(
                        context, artist, track, song.album, song.durationMs / 1000
                    )
                }
                val p = raw?.let { LrcParser.parse(it) }
                if (p != null) parsed = p else notAvailable = true
            }
        } catch (e: Exception) {
            notAvailable = true
        } finally {
            // CRITICAL (v2.5.26): SIEMPRE resetear loading, incluso si la
            // coroutine fue cancelada (CancellationException no se catchea
            // arriba porque es Exception, pero por seguridad lo ponemos aquí).
            loading = false
        }
    }

    val lyrics = parsed
    val positionMs = state.positionMs
    val activeIndex = if (lyrics != null) LrcParser.activeLineIndex(lyrics, positionMs) else -1

    // Auto-scroll so the active line stays visible (activeIndex - 3).
    LaunchedEffect(activeIndex) {
        if (lyrics != null && activeIndex > 3) {
            try {
                listState.animateScrollToItem((activeIndex - 3).coerceAtLeast(0))
            } catch (e: Exception) {
            }
        }
    }

    // CRITICAL (v2.5.23 UI): modelo de imagen para el background.
    // Si el song tiene artUri, usarlo; si no, el background queda solo con
    // el scrim oscuro (Color.Black alpha 0.75) sobre el fondo del Surface.
    val artModel: Any? = current?.let { song ->
        val artUri = song.artUri
        when {
            artUri.isNullOrBlank() -> null
            artUri.startsWith("content") -> android.net.Uri.parse(artUri)
            artUri.startsWith("/") -> File(artUri)
            else -> artUri
        }
    }

    // Box que envuelve todo, con la portada del album como background + scrim oscuro.
    Box(Modifier.fillMaxSize()) {
        // Layer 1: portada del album como background (fillMaxSize, ContentScale.Crop).
        if (artModel != null) {
            AsyncImage(
                model = artModel,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
        // Layer 2: scrim oscuro encima de la portada para que la letra sea legible.
        // Color.Black con alpha 0.75 — oscurece la portada pero deja entrever algo.
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.75f))
        )

        // Layer 3: contenido (top bar + lyrics) encima del scrim.
        Column(Modifier.fillMaxSize()) {
            // Top bar: back + title only.
            // CRITICAL (v2.5.23 UI): padding top con WindowInsets.statusBars para
            // que la flecha de back y el título "Letra" no se corten con la
            // status bar (batería/hora/wifi) del sistema.
            Row(
                Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(horizontal = 4.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.Filled.ArrowBack,
                        contentDescription = t("back"),
                        tint = Color.White
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    t("lyrics"),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }

            Box(Modifier.fillMaxSize().padding(bottom = 24.dp)) {
                when {
                    loading -> {
                        Column(
                            Modifier.fillMaxSize(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.height(12.dp))
                            Text(
                                t("searching_lyrics"),
                                color = Color.White.copy(alpha = 0.7f)
                            )
                        }
                    }
                    notAvailable -> {
                        Column(
                            Modifier.fillMaxSize(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                Icons.Filled.MusicOff,
                                contentDescription = null,
                                tint = Color.White.copy(alpha = 0.5f),
                                modifier = Modifier.padding(bottom = 8.dp)
                            )
                            Text(
                                t("lyrics_not_available"),
                                color = Color.White.copy(alpha = 0.7f)
                            )
                        }
                    }
                    lyrics != null -> {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            itemsIndexed(lyrics.lines) { i, line ->
                                val active = i == activeIndex
                                Text(
                                    line.text.ifBlank { " " },
                                    style = if (active) {
                                        MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                                    } else {
                                        MaterialTheme.typography.bodyLarge
                                    },
                                    // CRITICAL (v2.5.23 UI): colores blancos para
                                    // contraste con el fondo oscuro (portada + scrim).
                                    // Línea activa: blanco sólido. Inactivas: gris claro.
                                    color = if (active) {
                                        Color.White
                                    } else {
                                        Color.White.copy(alpha = 0.5f)
                                    },
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 10.dp)
                                )
                            }
                        }
                    }
                    else -> {
                        // Initial state (no song yet).
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = Color.White)
                        }
                    }
                }
            }
        }
    }
}
