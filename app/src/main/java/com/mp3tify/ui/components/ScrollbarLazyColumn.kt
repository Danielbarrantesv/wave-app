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
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
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
    val showUp by remember(scrollState) { derivedStateOf { scrollState.canScrollBackward } }
    val showDown by remember(scrollState) { derivedStateOf { scrollState.canScrollForward } }
    AnimatedVisibility(visible = showUp || showDown, enter = fadeIn(), exit = fadeOut(), modifier = modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (showUp) {
                Surface(onClick = { scope.launch { if (scrollState.firstVisibleItemIndex > 15) scrollState.scrollToItem(15); scrollState.animateScrollToItem(0) } }, shape = CircleShape, color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.92f), shadowElevation = 4.dp, modifier = Modifier.size(40.dp)) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Icon(Icons.Filled.KeyboardArrowUp, contentDescription = null, tint = MaterialTheme.colorScheme.primary) }
                }
            }
            if (showDown) {
                Surface(onClick = { scope.launch { val last = (scrollState.layoutInfo.totalItemsCount - 1).coerceAtLeast(0); if (last - scrollState.firstVisibleItemIndex > 15) scrollState.scrollToItem((last - 15).coerceAtLeast(0)); scrollState.animateScrollToItem(last) } }, shape = CircleShape, color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.92f), shadowElevation = 4.dp, modifier = Modifier.size(40.dp)) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Icon(Icons.Filled.KeyboardArrowDown, contentDescription = null, tint = MaterialTheme.colorScheme.primary) }
                }
            }
        }
    }
}
