package com.mp3tify.ui.screens.equalizer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.mp3tify.equalizer.EqualizerManager
import com.mp3tify.ui.t
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun EqualizerScreen(onBack: () -> Unit) {
    var initialized by remember { mutableStateOf(false) }
    var enabled by remember { mutableStateOf(true) }
    var bandCount by remember { mutableStateOf(5) }
    var levels by remember { mutableStateOf(listOf(0.5f, 0.5f, 0.5f, 0.5f, 0.5f)) }
    var selectedPreset by remember { mutableStateOf(-1) }
    var retryTrigger by remember { mutableStateOf(false) }

    val minLevel = EqualizerManager.getMinBandLevel()
    val maxLevel = EqualizerManager.getMaxBandLevel()
    val range = (maxLevel - minLevel).coerceAtLeast(1)

    // Init once the screen opens (needs an active audio session from the player).
    // Also re-runs when retryTrigger flips (user taps Reintentar).
    LaunchedEffect(retryTrigger) {
        withContext(Dispatchers.IO) {
            val ok = EqualizerManager.init()
            if (ok) {
                EqualizerManager.setEnabled(true)
                val count = EqualizerManager.getBandCount()
                val lvls = (0 until count).map { i ->
                    val lvl = EqualizerManager.getBandLevel(i.toShort())
                    ((lvl - minLevel).toFloat() / range).coerceIn(0f, 1f)
                }
                initialized = true
                bandCount = count
                levels = lvls
            }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            // CRITICAL #1: windowInsetsPadding(WindowInsets.statusBars) para que el
            // Row del topBar NO se superponga con la status bar (bateria/hora/wifi).
            // Antes este Row custom no aplicaba insets y los iconos quedaban pegados
            // arriba, mezclados con la status bar. Tambien navigationBars en bottom
            // para landscape.
            Row(
                Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(horizontal = 4.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = t("back"))
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    t("equalizer"),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                Button(onClick = {
                    enabled = !enabled
                    EqualizerManager.setEnabled(enabled)
                }) {
                    Text(if (enabled) "ON" else "OFF")
                }
                Spacer(Modifier.width(12.dp))
            }
        }
    ) { padding ->
        // #1 verticalScroll para landscape: si el contenido (presets + sliders + labels)
        // no cabe en pantalla horizontal, se hace scroll vertical.
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
                .windowInsetsPadding(WindowInsets.navigationBars)
        ) {
            if (!initialized) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 48.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            t("equalizer_needs_audio"),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(Modifier.height(16.dp))
                        Button(onClick = {
                            EqualizerManager.release()
                            retryTrigger = !retryTrigger
                        }) {
                            Text(t("retry"))
                        }
                    }
                }
            } else {
                // Preset chips
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(EqualizerManager.PRESETS.size) { i ->
                        val preset = EqualizerManager.PRESETS[i]
                        FilterChip(
                            selected = selectedPreset == i,
                            onClick = {
                                selectedPreset = i
                                EqualizerManager.applyPreset(preset)
                                levels = preset.gains
                                    .take(bandCount)
                                    .map { ((it - minLevel).toFloat() / range).coerceIn(0f, 1f) }
                            },
                            label = { Text(t(preset.key)) }
                        )
                    }
                }

                Spacer(Modifier.height(24.dp))

                // Band sliders (rotated → vertical) with frequency labels.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    for (band in 0 until bandCount) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(
                                Modifier
                                    .width(52.dp)
                                    .height(200.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Slider(
                                    value = levels.getOrElse(band) { 0.5f },
                                    onValueChange = { v ->
                                        levels = levels.toMutableList().also {
                                            if (band < it.size) it[band] = v
                                        }
                                        selectedPreset = -1
                                        EqualizerManager.setBandGain(
                                            band.toShort(),
                                            (minLevel + v * range).toInt()
                                        )
                                    },
                                    modifier = Modifier
                                        .graphicsLayer {
                                            rotationZ = 270f
                                            transformOrigin = TransformOrigin(0.5f, 0.5f)
                                        }
                                        .width(200.dp)
                                )
                            }
                            Text(
                                EqualizerManager.getBandFreqLabel(band.toShort()),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}
