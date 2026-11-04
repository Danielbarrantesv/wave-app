package com.mp3tify.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
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
