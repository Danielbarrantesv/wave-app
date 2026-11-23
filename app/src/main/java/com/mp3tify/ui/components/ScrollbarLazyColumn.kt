package com.mp3tify.ui.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun ScrollbarLazyColumn(
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(),
    contentPadding: PaddingValues = PaddingValues(0.dp),
    verticalArrangement: androidx.compose.foundation.layout.Arrangement.Vertical =
        androidx.compose.foundation.layout.Arrangement.Top,
    content: LazyListScope.() -> Unit
) {
    LazyColumn(
        modifier = modifier, state = state, contentPadding = contentPadding,
        verticalArrangement = verticalArrangement, content = content
    )
}

/**
 * Detecta la dirección del scroll píxel a píxel (no solo por cambio de índice)
 * para ocultar la barra al bajar y mostrarla al subir tanto en Library como en Playlist.
 */
@Composable
fun rememberAutoHideToolbar(
    scrollState: LazyListState,
    thresholdPx: Int = 16
): Boolean {
    var isVisible by remember { mutableStateOf(true) }
    LaunchedEffect(scrollState) {
        var prevIndex = scrollState.firstVisibleItemIndex
        var prevOffset = scrollState.firstVisibleItemScrollOffset
        snapshotFlow {
            Triple(
                scrollState.firstVisibleItemIndex,
                scrollState.firstVisibleItemScrollOffset,
                scrollState.isScrollInProgress
            )
        }.collect { (index, offset, scrolling) ->
            if (index == 0 && offset == 0) {
                isVisible = true
            } else if (scrolling) {
                if (index != prevIndex) {
                    isVisible = index < prevIndex
                } else {
                    val delta = offset - prevOffset
                    if (delta > thresholdPx) isVisible = false
                    else if (delta < -thresholdPx) isVisible = true
                }
            }
            prevIndex = index
            prevOffset = offset
        }
    }
    return isVisible
}
