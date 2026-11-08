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
fun PlayerBar(
    state: PlaybackState,
    onOpenNowPlaying: () -> Unit
) {
    val current = state.playlist.getOrNull(state.currentWindow)

    // seekPosition local. null = no está arrastrando, usa el progreso real del
    // PlaybackState. != null = está arrastrando, usa el valor local para evitar
    // jitter mientras seekTo se hace efectivo.
    var seekPosition by remember { mutableStateOf<Float?>(null) }
    val progress = if (state.durationMs > 0) {
        (state.positionMs.toFloat() / state.durationMs).coerceIn(0f, 1f)
    } else {
        0f
    }
    val sliderValue = seekPosition ?: progress

    AnimatedVisibility(visible = current != null) {
        // CRITICAL #1: navigationBarsPadding() empuja el PlayerBar por encima de la
        // gestural nav bar. Sin esto, en modelos con nav bar de 3 botones o gesture
        // pill, los botones prev/play/next quedan tapados por la nav bar del sistema.
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.navigationBarsPadding()
        ) {
            Column {
                // CRITICAL (v2.5.22 UI): SeekSlider custom con bolita visible siempre.
                SeekSlider(
                    progress = sliderValue,
                    durationMs = state.durationMs,
                    onSeekStart = { /* no-op */ },
                    onSeekChange = { newValue ->
                        if (state.durationMs > 0) {
                            seekPosition = newValue
                        }
                    },
                    onSeekEnd = {
                        seekPosition?.let { pos ->
                            val targetMs = (pos * state.durationMs).toLong()
                            try { PlaybackController.seekTo(targetMs) } catch (e: Exception) { e.printStackTrace() }
                        }
                        seekPosition = null
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp)
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
    val touchTargetHeightDp = 24.dp  // alto del área táctil (más grande que el track para fácil agarrar)

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
