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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
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
import com.mp3tify.playback.PlaybackController
import com.mp3tify.playback.PlaybackState
import com.mp3tify.ui.t

/**
 * Mini player bar: cover + title/artist + prev/play/next + seek slider.
 *
 * CRITICAL (v2.5.13 UI): la barra de progreso delgada (LinearProgressIndicator de
 * 2dp) fue reemplazada por un Slider Material 3 con thumb visible siempre, para
 * que el usuario pueda arrastrar y hacer seek sin tener que abrir la pantalla
 * Now Playing. Mientras arrastra, seekPosition != null y el Slider usa ese valor
 * local en lugar del calculado desde PlaybackState (evita jitter mientras el
 * seek no se hace efectivo).
 */
@Composable
fun PlayerBar(
    state: PlaybackState,
    onOpenNowPlaying: () -> Unit
) {
    val current = state.playlist.getOrNull(state.currentWindow)

    // CRITICAL (v2.5.13 UI): seekPosition local. null = no está arrastrando,
    // usa el progreso real del PlaybackState. != null = está arrastrando,
    // usa el valor local para evitar jitter mientras seekTo se hace efectivo.
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
                // CRITICAL (v2.5.13 UI): Slider con thumb visible siempre.
                // El Slider Material 3 muestra el thumb automáticamente cuando es
                // interactiva (cuando el valor puede cambiar). El usuario arrastra
                // el thumb para hacer seek; onValueChangeFinished hace seekTo al
                // soltar. Height más grande (8dp en el track) para que el thumb
                // (típicamente 12-16dp) no quede tapado.
                // NOTA: Slider ocupa toda la fila, así que el padding horizontal
                // ya está aplicado por el modifier.
                Slider(
                    value = sliderValue,
                    onValueChange = { newValue ->
                        // Solo permitir seek si la duración es conocida (>0).
                        if (state.durationMs > 0) {
                            seekPosition = newValue
                        }
                    },
                    onValueChangeFinished = {
                        // Al soltar el thumb: hacer seekTo y limpiar el state local.
                        seekPosition?.let { pos ->
                            val targetMs = (pos * state.durationMs).toLong()
                            try { PlaybackController.seekTo(targetMs) } catch (e: Exception) { e.printStackTrace() }
                        }
                        seekPosition = null
                    },
                    valueRange = 0f..1f,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(12.dp)
                        .padding(horizontal = 6.dp),
                    // Color del thumb y track: primary para lo "filled", outline para el resto.
                    // Material 3 Slider usa estos colores por defecto; los explicitamos
                    // para que el thumb sea visible del color primario.
                    colors = androidx.compose.material3.SliderDefaults.colors(
                        thumbColor = MaterialTheme.colorScheme.primary,
                        activeTrackColor = MaterialTheme.colorScheme.primary,
                        inactiveTrackColor = MaterialTheme.colorScheme.outline
                    )
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
