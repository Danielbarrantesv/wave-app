package com.mp3tify.ui.components
import com.mp3tify.data.model.displayArtist
import com.mp3tify.data.model.displayTitle

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import com.mp3tify.playback.PlaybackController
import com.mp3tify.playback.PlaybackState
import com.mp3tify.ui.t

/**
 * Mini player bar: cover + title/artist + prev/play/next + seek slider.
 *
 * CRITICAL (v2.5.22 UI): el Slider Material 3 fue reemplazado por un slider
 * custom (SeekSlider) que dibuja una BOLITA (thumb) SIEMPRE visible del color
 * primario, con tamaño grande (16dp diámetro) para que sea fácil de agarrar
 * y arrastrar. El Slider Material 3 por defecto tiene un thumb que solo se
 * ve bien cuando lo tocas — en reposo es muy chiquito/difícil de ver.
 *
 * El usuario reportó que quería "una bolita en la línea de tiempo para
 * arrastrar para adelantar o retroceder". Este slider custom resuelve eso.
 */
@Composable
fun LivePlayerBar(onOpenNowPlaying: () -> Unit) {
    val meta by PlaybackController.barMeta.collectAsState()
    val compatState = remember(meta) { PlaybackState(isPlaying = meta.isPlaying, currentWindow = if (meta.currentSong != null) 0 else -1, durationMs = meta.durationMs, playlist = listOfNotNull(meta.currentSong)) }
    PlayerBar(state = compatState, onOpenNowPlaying = onOpenNowPlaying, livePosition = true)
}
@Composable
private fun LiveSeekSlider(durationMs: Long, modifier: Modifier = Modifier) {
    val posState = PlaybackController.positionMs.collectAsState()
    val pc = MaterialTheme.colorScheme.primary; val oc = MaterialTheme.colorScheme.outline; val d = LocalDensity.current
    val tr = remember(d) { with(d) { 8.dp.toPx() } }; val th = remember(d) { with(d) { 3.dp.toPx() } }
    var drag by remember { mutableStateOf(false) }; var dv by remember { mutableStateOf(0f) }
    Canvas(modifier = modifier.height(16.dp).pointerInput(durationMs) {
        detectHorizontalDragGestures(
            onDragStart = { o -> if (durationMs > 0) { drag = true; dv = (o.x / size.width).coerceIn(0f, 1f) } },
            onHorizontalDrag = { ch, da -> if (drag) { ch.consume(); dv = (dv + da / size.width).coerceIn(0f, 1f) } },
            onDragEnd = { if (drag) { drag = false; try { PlaybackController.seekTo((dv * durationMs).toLong()) } catch (e: Exception) { e.printStackTrace() } } },
            onDragCancel = { drag = false }
        )
    }) {
        val v = if (drag) dv else if (durationMs > 0) (posState.value.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
        val w = size.width; val cy = size.height / 2f
        drawRoundRect(color = oc, topLeft = Offset(0f, cy - th / 2), size = Size(w, th), cornerRadius = CornerRadius(th / 2))
        if (v * w > 0f) drawRoundRect(color = pc, topLeft = Offset(0f, cy - th / 2), size = Size(v * w, th), cornerRadius = CornerRadius(th / 2))
        drawCircle(color = pc, radius = tr, center = Offset(v * w, cy))
    }
}

@Composable
fun PlayerBar(
    state: PlaybackState,
    onOpenNowPlaying: () -> Unit,
    livePosition: Boolean = false
) {
    val current = state.playlist.getOrNull(state.currentWindow)

    // seekPosition local. null = no está arrastrando, usa el progreso real del
    // PlaybackState. != null = está arrastrando, usa el valor local para evitar
    // jitter mientras seekTo se hace efectivo.
    var seekPosition by remember { mutableStateOf<Float?>(null) }
    AnimatedVisibility(visible = current != null) {
        // CRITICAL #1: navigationBarsPadding() empuja el PlayerBar por encima de la
        // gestural nav bar. Sin esto, en modelos con nav bar de 3 botones o gesture
        // pill, los botones prev/play/next quedan tapados por la nav bar del sistema.
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.navigationBarsPadding()
        ) {
            Column {
                if (livePosition) {
                    LiveSeekSlider(durationMs = state.durationMs, modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 2.dp, bottom = 0.dp))
                } else {
                    val progress = if (state.durationMs > 0) (state.positionMs.toFloat() / state.durationMs).coerceIn(0f, 1f) else 0f
                    SeekSlider(progress = seekPosition ?: progress, durationMs = state.durationMs, onSeekStart = {}, onSeekChange = { if (state.durationMs > 0) seekPosition = it }, onSeekEnd = { seekPosition?.let { try { PlaybackController.seekTo((it * state.durationMs).toLong()) } catch (e: Exception) { e.printStackTrace() } }; seekPosition = null }, modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 2.dp, bottom = 0.dp))
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenNowPlaying() }
                        // CRITICAL (v2.5.40): gap reducido al mínimo entre timeline y controles.
                        .padding(start = 12.dp, end = 12.dp, top = 0.dp, bottom = 6.dp),
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

/**
 * CRITICAL (v2.5.22 UI): slider custom con BOLITA visible siempre.
 *
 * Dibuja:
 *   - Track inactivo (línea delgada del color outline, 2dp alto)
 *   - Track activo (línea del color primario desde 0 hasta el progreso)
 *   - Thumb (BOLITA del color primario, 16dp diámetro) SIEMPRE visible
 *
 * Detecta drag horizontal con pointerInput + detectHorizontalDragGestures.
 * El usuario puede agarrar la bolita o tocar cualquier punto del track
 * para saltar a esa posición.
 *
 * Mientras arrastra, onSeekChange actualiza el valor local del caller
 * (para evitar jitter). Al soltar, onSeekEnd hace seekTo definitivo.
 */
@Composable
fun SeekSlider(
    progress: Float,
    durationMs: Long,
    onSeekStart: () -> Unit,
    onSeekChange: (Float) -> Unit,
    onSeekEnd: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Colors del MaterialTheme (Canvas no los accede directamente)
    val primaryColor = MaterialTheme.colorScheme.primary
    val outlineColor = MaterialTheme.colorScheme.outline

    // Tamaños en px (Canvas trabaja con px)
    val density = LocalDensity.current
    val thumbRadiusPx = with(density) { 8.dp.toPx() }  // 16dp diámetro
    val trackHeightPx = with(density) { 3.dp.toPx() }   // 3dp alto del track
    val touchTargetHeightDp = 16.dp  // alto compacto para acercar timeline y controles

    // State local para el drag
    var isDragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableStateOf(0f) }
    val displayValue = if (isDragging) dragValue else progress

    Canvas(
        modifier = modifier
            .height(touchTargetHeightDp)
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        // Al empezar el drag, saltar a la posición del toque inicial.
                        isDragging = true
                        val newValue = (offset.x / size.width).coerceIn(0f, 1f)
                        dragValue = newValue
                        onSeekStart()
                        onSeekChange(newValue)
                    },
                    onHorizontalDrag = { change, dragAmount ->
                        change.consume()
                        val newValue = (dragValue + dragAmount / size.width).coerceIn(0f, 1f)
                        dragValue = newValue
                        onSeekChange(newValue)
                    },
                    onDragEnd = {
                        isDragging = false
                        onSeekEnd()
                    },
                    onDragCancel = {
                        isDragging = false
                        onSeekEnd()
                    }
                )
            }
    ) {
        val canvasWidth = size.width
        val centerY = size.height / 2

        // Track inactivo (línea delgada del color outline)
        drawRoundRect(
            color = outlineColor,
            topLeft = Offset(0f, centerY - trackHeightPx / 2),
            size = Size(canvasWidth, trackHeightPx),
            cornerRadius = CornerRadius(trackHeightPx / 2)
        )

        // Track activo (línea del color primario desde 0 hasta el progreso)
        val activeWidth = displayValue * canvasWidth
        if (activeWidth > 0f) {
            drawRoundRect(
                color = primaryColor,
                topLeft = Offset(0f, centerY - trackHeightPx / 2),
                size = Size(activeWidth, trackHeightPx),
                cornerRadius = CornerRadius(trackHeightPx / 2)
            )
        }

        // Thumb (BOLITA del color primario, 16dp diámetro) SIEMPRE visible.
        // Posición X basada en el progreso. Centrada verticalmente.
        val thumbX = displayValue * canvasWidth
        drawCircle(
            color = primaryColor,
            radius = thumbRadiusPx,
            center = Offset(thumbX, centerY)
        )
    }
}
