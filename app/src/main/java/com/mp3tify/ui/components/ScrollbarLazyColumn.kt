package com.mp3tify.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import kotlinx.coroutines.Job
import kotlin.math.roundToInt
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mp3tify.ui.tf

@Composable
fun ScrollbarLazyColumn(modifier: Modifier = Modifier, state: LazyListState = rememberLazyListState(), contentPadding: PaddingValues = PaddingValues(0.dp), verticalArrangement: androidx.compose.foundation.layout.Arrangement.Vertical = androidx.compose.foundation.layout.Arrangement.Top, content: LazyListScope.() -> Unit) {
    LazyColumn(modifier = modifier, state = state, contentPadding = contentPadding, verticalArrangement = verticalArrangement, content = content)
}

@Composable
fun rememberAutoHideToolbar(scrollState: LazyListState, thresholdPx: Int = 28): Boolean {
    var isVisible by remember { mutableStateOf(true) }
    LaunchedEffect(scrollState) {
        var prevIndex = scrollState.firstVisibleItemIndex; var prevOffset = scrollState.firstVisibleItemScrollOffset
        snapshotFlow { (scrollState.firstVisibleItemIndex.toLong() shl 32) or (scrollState.firstVisibleItemScrollOffset.toLong() and 0xFFFFFFFFL) }.collect { packed ->
            val index = (packed shr 32).toInt(); val offset = packed.toInt()
            if ((index == 0 && offset == 0) || !scrollState.canScrollBackward) isVisible = true
            else if (scrollState.isScrollInProgress) {
                if (index != prevIndex) isVisible = index < prevIndex
                else { val d = offset - prevOffset; if (d > thresholdPx) isVisible = false else if (d < -thresholdPx) isVisible = true }
            }
            prevIndex = index; prevOffset = offset
        }
    }
    return isVisible
}

@Composable
fun AutoHideCountToolbar(scrollState: LazyListState, count: Int, isGrid: Boolean, onToggleView: () -> Unit, sleepTimerActive: Boolean, onOpenSleepTimer: () -> Unit, modifier: Modifier = Modifier) {
    val visible = rememberAutoHideToolbar(scrollState)
    AnimatedVisibility(visible = visible, enter = slideInVertically { -it } + fadeIn(), exit = slideOutVertically { -it } + fadeOut(), modifier = modifier) {
        Surface(color = MaterialTheme.colorScheme.background.copy(alpha = 0.96f)) {
            Row(modifier = Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(tf("songs_count", count), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                IconButton(onClick = onToggleView) { Icon(if (isGrid) Icons.Filled.List else Icons.Filled.Apps, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                IconButton(onClick = onOpenSleepTimer) { Icon(Icons.Filled.Schedule, contentDescription = null, tint = if (sleepTimerActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
}
@Composable
fun ScrollJumpButtons(scrollState: LazyListState, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val isScrollable by remember(scrollState) { derivedStateOf { scrollState.canScrollBackward || scrollState.canScrollForward } }
    AnimatedVisibility(visible = isScrollable, enter = fadeIn(), exit = fadeOut(), modifier = modifier.fillMaxHeight()) {
        var trackHeightPx by remember { mutableFloatStateOf(0f) }
        var isDragging by remember { mutableStateOf(false) }
        var dragFraction by remember { mutableFloatStateOf(0f) }
        val totalItems by remember(scrollState) { derivedStateOf { scrollState.layoutInfo.totalItemsCount } }
        val visibleCount by remember(scrollState) { derivedStateOf { scrollState.layoutInfo.visibleItemsInfo.size.coerceAtLeast(1) } }
        val listFraction by remember(scrollState) { derivedStateOf { if (!scrollState.canScrollForward && scrollState.canScrollBackward) 1f else (scrollState.firstVisibleItemIndex.toFloat() / (totalItems - visibleCount).coerceAtLeast(1).toFloat()).coerceIn(0f, 1f) } }
        val fraction = if (isDragging) dragFraction else listFraction
        val thumbHeightPx = with(density) { val raw = if (totalItems > 0) trackHeightPx * (visibleCount.toFloat() / totalItems.toFloat()) else 48.dp.toPx(); raw.coerceIn(40.dp.toPx(), 88.dp.toPx()).coerceAtMost((trackHeightPx * 0.6f).coerceAtLeast(40.dp.toPx())) }
        val thumbOffsetY = (fraction * (trackHeightPx - thumbHeightPx).coerceAtLeast(1f)).roundToInt()
        val thumbWidth by animateDpAsState(if (isDragging) 9.dp else 5.dp, label = "sbW")
        val thumbAlpha by animateFloatAsState(if (isDragging || scrollState.isScrollInProgress) 0.95f else 0.55f, label = "sbA")
        Box(Modifier.fillMaxHeight().width(24.dp).onSizeChanged { trackHeightPx = it.height.toFloat() }.pointerInput(scrollState, trackHeightPx, thumbHeightPx) {
            var job: Job? = null
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                isDragging = true
                fun jumpTo(y: Float) {
                    val f = ((y - thumbHeightPx / 2f) / (trackHeightPx - thumbHeightPx).coerceAtLeast(1f)).coerceIn(0f, 1f)
                    dragFraction = f
                    val tot = scrollState.layoutInfo.totalItemsCount; val vis = scrollState.layoutInfo.visibleItemsInfo.size.coerceAtLeast(1)
                    val target = if (f >= 0.995f) (tot - 1).coerceAtLeast(0) else (f * (tot - vis).coerceAtLeast(0)).roundToInt().coerceIn(0, (tot - 1).coerceAtLeast(0))
                    job?.cancel(); job = scope.launch { scrollState.scrollToItem(target) }
                }
                try { jumpTo(down.position.y); drag(down.id) { c -> c.consume(); jumpTo(c.position.y) } } finally { isDragging = false }
            }
        }, contentAlignment = Alignment.TopEnd) {
            Box(Modifier.padding(end = 3.dp).fillMaxHeight().width(3.dp).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f), CircleShape))
            if (trackHeightPx > 0f) Box(Modifier.offset { IntOffset(0, thumbOffsetY) }.padding(end = 2.dp).width(thumbWidth).height(with(density) { thumbHeightPx.toDp() }).background(MaterialTheme.colorScheme.primary.copy(alpha = thumbAlpha), CircleShape))
        }
    }
}
