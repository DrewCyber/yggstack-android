package link.yggdrasil.yggstackng.android.ui.configuration

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import kotlin.math.roundToInt

@Composable
fun <T : Any> ReorderableColumn(
    items: List<T>,
    onReorder: (List<T>) -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    itemContent: @Composable (item: T, isDragging: Boolean) -> Unit
) {
    // Use a stable MutableState with no key so the same object is captured
    // by all pointerInput coroutines for the lifetime of this composable.
    // Sync with the external list only when no drag is in progress.
    var currentItems by remember { mutableStateOf(items) }
    var draggedItem by remember { mutableStateOf<T?>(null) }
    var dragOffsetY by remember { mutableStateOf(0f) }
    val itemHeights = remember { mutableStateMapOf<T, Float>() }

    // Always-fresh reference to `items` for use inside pointerInput coroutine closures.
    // pointerInput(item) only restarts when the item key changes, so without this the
    // `items` captured in onDragEnd / onDragCancel would be stale after the first reorder,
    // causing the "jumps back to previous position" bug.
    val latestItems = rememberUpdatedState(items)

    if (draggedItem == null) currentItems = items

    val nestedScrollConnection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset =
                if (draggedItem != null) available else Offset.Zero
        }
    }

    Layout(
        modifier = modifier.nestedScroll(nestedScrollConnection),
        content = {
            currentItems.forEach { item ->
                key(item) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(
                                if (enabled) Modifier.pointerInput(item) {
                                    detectDragGesturesAfterLongPress(
                                        onDragStart = { _ ->
                                            draggedItem = item
                                            dragOffsetY = 0f
                                        },
                                        onDrag = { change, dragAmount ->
                                            change.consume()
                                            dragOffsetY += dragAmount.y
                                            val currentIndex = currentItems.indexOf(item)
                                            val itemH = itemHeights[item]
                                                ?: return@detectDragGesturesAfterLongPress
                                            when {
                                                dragOffsetY > itemH * 0.5f &&
                                                        currentIndex < currentItems.size - 1 -> {
                                                    val neighbor = currentItems[currentIndex + 1]
                                                    val neighborH = itemHeights[neighbor] ?: itemH
                                                    currentItems = currentItems.toMutableList()
                                                        .also { list ->
                                                            list.removeAt(currentIndex)
                                                            list.add(currentIndex + 1, item)
                                                        }
                                                    dragOffsetY -= neighborH
                                                }
                                                dragOffsetY < -itemH * 0.5f && currentIndex > 0 -> {
                                                    val neighbor = currentItems[currentIndex - 1]
                                                    val neighborH = itemHeights[neighbor] ?: itemH
                                                    currentItems = currentItems.toMutableList()
                                                        .also { list ->
                                                            list.removeAt(currentIndex)
                                                            list.add(currentIndex - 1, item)
                                                        }
                                                    dragOffsetY += neighborH
                                                }
                                            }
                                        },
                                        onDragEnd = {
                                            val finalItems = currentItems
                                            draggedItem = null
                                            dragOffsetY = 0f
                                            if (finalItems != latestItems.value) onReorder(finalItems)
                                        },
                                        onDragCancel = {
                                            currentItems = latestItems.value
                                            draggedItem = null
                                            dragOffsetY = 0f
                                        }
                                    )
                                } else Modifier
                            )
                    ) {
                        itemContent(item, item == draggedItem)
                    }
                }
            }
        }
    ) { measurables, constraints ->
        val placeables = measurables.mapIndexed { index, measurable ->
            val placeable = measurable.measure(constraints.copy(minHeight = 0))
            currentItems.getOrNull(index)?.let { itemHeights[it] = placeable.height.toFloat() }
            placeable
        }

        val totalHeight = placeables.sumOf { it.height }

        layout(constraints.maxWidth, totalHeight) {
            var yAccum = 0
            placeables.forEachIndexed { index, placeable ->
                val item = currentItems.getOrNull(index)
                val isDragging = item != null && item == draggedItem
                placeable.placeWithLayer(
                    x = 0,
                    y = yAccum + if (isDragging) dragOffsetY.roundToInt() else 0,
                    zIndex = if (isDragging) 1f else 0f
                )
                yAccum += placeable.height
            }
        }
    }
}
