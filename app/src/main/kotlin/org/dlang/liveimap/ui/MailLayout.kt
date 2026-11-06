package org.dlang.liveimap.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import org.dlang.liveimap.window.FillResult
import org.dlang.liveimap.window.FillSlot
import org.dlang.liveimap.window.LayoutNode
import org.dlang.liveimap.window.LayoutRecord
import org.dlang.liveimap.window.SplitDirection
import org.dlang.liveimap.window.panels

/**
 * Draws [record] with layers already assigned by [fillResult].
 * A zoom fill is one slot and does not walk the record.
 * Dragged sizes stay in [sizes] for this window.
 */
@Composable
internal fun MailLayout(
    record: LayoutRecord,
    fillResult: FillResult,
    sizes: SnapshotStateMap<String, Float>,
    onKey: (String) -> Unit,
    panel: @Composable (FillSlot) -> Unit,
) {
    val zoomed = fillResult.slots.singleOrNull()?.panelIds == listOf("zoom")
    val root = record.root
    if (zoomed || root == null) {
        val slot = fillResult.slots.firstOrNull { !it.collapsed } ?: return
        KeyedPanel(slot.panelIds.last(), onKey) { panel(slot) }
        return
    }
    LayoutWalk(root, "", fillResult, sizes, onKey, panel)
}

@Composable
private fun LayoutWalk(
    node: LayoutNode,
    key: String,
    fillResult: FillResult,
    sizes: SnapshotStateMap<String, Float>,
    onKey: (String) -> Unit,
    panel: @Composable (FillSlot) -> Unit,
) {
    val ids = node.panels().map { it.id }
    val covered = fillResult.slots.firstOrNull { it.panelIds.size > 1 && it.panelIds.toSet() == ids.toSet() }
    if (covered != null) {
        if (!covered.collapsed) KeyedPanel(covered.panelIds.last(), onKey) { panel(covered) }
        return
    }
    when (node) {
        is LayoutNode.Panel -> {
            val slot = fillResult.slots.firstOrNull { node.panel.id in it.panelIds } ?: return
            if (slot.collapsed || slot.panelIds.size != 1) return
            KeyedPanel(node.panel.id, onKey) { panel(slot) }
        }
        is LayoutNode.Split -> {
            if (node.first.collapsedIn(fillResult) && node.second.collapsedIn(fillResult)) return
            if (node.first.collapsedIn(fillResult)) {
                LayoutWalk(node.second, key + "1", fillResult, sizes, onKey, panel)
                return
            }
            if (node.second.collapsedIn(fillResult)) {
                LayoutWalk(node.first, key + "0", fillResult, sizes, onKey, panel)
                return
            }
            val beside = node.direction == SplitDirection.Beside
            AxisSplit(
                axis = if (beside) PaneAxis.SideBySide else PaneAxis.TopBottom,
                leadingDp = sizes[key] ?: -1f,
                fallback = { span -> ((span - 8f) * node.share).coerceAtLeast(0f) },
                onSpan = { _ -> },
                onDrag = { delta, span ->
                    val max = (span - 8f).coerceAtLeast(0f)
                    val base = sizes[key] ?: ((span - 8f) * node.share)
                    sizes[key] = (base + delta).coerceIn(0f, max)
                },
                leading = { LayoutWalk(node.first, key + "0", fillResult, sizes, onKey, panel) },
                trailing = { LayoutWalk(node.second, key + "1", fillResult, sizes, onKey, panel) },
            )
        }
    }
}

private fun LayoutNode.collapsedIn(fillResult: FillResult): Boolean {
    val ids = panels().map { it.id }.toSet()
    if (ids.isEmpty()) return true
    return ids.all { id ->
        val slot = fillResult.slots.firstOrNull { id in it.panelIds } ?: return@all true
        slot.collapsed
    }
}

@Composable
private fun KeyedPanel(id: String, onKey: (String) -> Unit, content: @Composable () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(id) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.changes.any { it.pressed }) onKey(id)
                    }
                }
            },
    ) {
        content()
    }
}
