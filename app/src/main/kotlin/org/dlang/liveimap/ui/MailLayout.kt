package org.dlang.liveimap.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.focusTarget
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import org.dlang.liveimap.i18n.S
import org.dlang.liveimap.window.FillResult
import org.dlang.liveimap.window.FillSlot
import org.dlang.liveimap.window.LayoutNode
import org.dlang.liveimap.window.LayoutRecord
import org.dlang.liveimap.window.SplitDirection
import org.dlang.liveimap.window.WindowLayer
import org.dlang.liveimap.window.panels

/**
 * Draws [record] with layers already assigned by [fillResult].
 * A zoom fill is one slot and does not walk the record.
 * A covering slot is one span of its panels, including when those panels are not one subtree.
 * [keyPanelId] is the panel that has the keys. It stays there when another layer is on top of the stack.
 * Dragged sizes stay in [sizes] for this window.
 */
@Composable
internal fun MailLayout(
    record: LayoutRecord,
    fillResult: FillResult,
    sizes: SnapshotStateMap<String, Float>,
    keyPanelId: String?,
    keyLayer: WindowLayer?,
    onKey: (String) -> Unit,
    panel: @Composable (FillSlot) -> Unit,
) {
    val zoomed = fillResult.slots.singleOrNull()?.panelIds == listOf("zoom")
    val root = record.root
    if (zoomed || root == null) {
        val slot = fillResult.slots.firstOrNull { !it.collapsed } ?: return
        KeyedPanel(slot.panelIds.last(), slot.holdsKeys(keyPanelId, keyLayer), onKey) { panel(slot) }
        return
    }
    if (coverIsOneSubtree(root, fillResult)) {
        LayoutWalk(root, "", fillResult, sizes, keyPanelId, keyLayer, onKey, panel)
    } else {
        FreeCover(root, fillResult, sizes, keyPanelId, keyLayer, onKey, panel)
    }
}

internal fun coverIsOneSubtree(root: LayoutNode, fillResult: FillResult): Boolean {
    val cover = fillResult.slots.firstOrNull { it.panelIds.size > 1 } ?: return true
    return subtreeMatches(root, cover.panelIds.toSet())
}

private fun subtreeMatches(node: LayoutNode, ids: Set<String>): Boolean {
    if (node.panels().map { it.id }.toSet() == ids) return true
    return when (node) {
        is LayoutNode.Panel -> false
        is LayoutNode.Split -> subtreeMatches(node.first, ids) || subtreeMatches(node.second, ids)
    }
}

/** The panel that has the keys. A different layer may be the top of the stack. */
internal fun slotHoldsKeys(slot: FillSlot, keyPanelId: String?, keyLayer: WindowLayer?): Boolean =
    keyPanelId != null && keyPanelId in slot.panelIds && slot.layer == keyLayer

private fun FillSlot.holdsKeys(keyPanelId: String?, keyLayer: WindowLayer?): Boolean =
    slotHoldsKeys(this, keyPanelId, keyLayer)

internal data class PanelBounds(val x: Float, val y: Float, val w: Float, val h: Float)

/** Panel rectangles in dp. A cover that is not one subtree still occupies one continuous region. */
internal fun panelRegions(
    root: LayoutNode,
    fillResult: FillResult,
    sizes: Map<String, Float>,
    width: Float,
    height: Float,
): Map<String, PanelBounds> {
    val out = LinkedHashMap<String, PanelBounds>()
    placeWithBars(root, "", PanelBounds(0f, 0f, width, height), fillResult, sizes, out, ArrayList())
    return out
}

private fun splitSitsInsideCover(node: LayoutNode.Split, fillResult: FillResult): Boolean {
    val cover = fillResult.slots.firstOrNull { it.panelIds.size > 1 } ?: return false
    val ids = cover.panelIds.toSet()
    return node.first.lastPanelId() in ids && node.second.firstPanelId() in ids
}

private fun LayoutNode.firstPanelId(): String = when (this) {
    is LayoutNode.Panel -> panel.id
    is LayoutNode.Split -> first.firstPanelId()
}

private fun LayoutNode.lastPanelId(): String = when (this) {
    is LayoutNode.Panel -> panel.id
    is LayoutNode.Split -> second.lastPanelId()
}

@Composable
private fun LayoutWalk(
    node: LayoutNode,
    key: String,
    fillResult: FillResult,
    sizes: SnapshotStateMap<String, Float>,
    keyPanelId: String?,
    keyLayer: WindowLayer?,
    onKey: (String) -> Unit,
    panel: @Composable (FillSlot) -> Unit,
) {
    val ids = node.panels().map { it.id }
    val covered = fillResult.slots.firstOrNull { it.panelIds.size > 1 && it.panelIds.toSet() == ids.toSet() }
    if (covered != null) {
        if (!covered.collapsed) {
            KeyedPanel(covered.panelIds.last(), covered.holdsKeys(keyPanelId, keyLayer), onKey) { panel(covered) }
        }
        return
    }
    when (node) {
        is LayoutNode.Panel -> {
            val slot = fillResult.slots.firstOrNull { node.panel.id in it.panelIds } ?: return
            if (slot.collapsed || slot.panelIds.size != 1) return
            KeyedPanel(node.panel.id, slot.holdsKeys(keyPanelId, keyLayer), onKey) { panel(slot) }
        }
        is LayoutNode.Split -> {
            if (node.first.collapsedIn(fillResult) && node.second.collapsedIn(fillResult)) return
            if (node.first.collapsedIn(fillResult)) {
                LayoutWalk(node.second, key + "1", fillResult, sizes, keyPanelId, keyLayer, onKey, panel)
                return
            }
            if (node.second.collapsedIn(fillResult)) {
                LayoutWalk(node.first, key + "0", fillResult, sizes, keyPanelId, keyLayer, onKey, panel)
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
                leading = { LayoutWalk(node.first, key + "0", fillResult, sizes, keyPanelId, keyLayer, onKey, panel) },
                trailing = { LayoutWalk(node.second, key + "1", fillResult, sizes, keyPanelId, keyLayer, onKey, panel) },
            )
        }
    }
}

@Composable
private fun FreeCover(
    root: LayoutNode,
    fillResult: FillResult,
    sizes: SnapshotStateMap<String, Float>,
    keyPanelId: String?,
    keyLayer: WindowLayer?,
    onKey: (String) -> Unit,
    panel: @Composable (FillSlot) -> Unit,
) {
    val bars = ArrayList<SplitBar>()
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val regions = LinkedHashMap<String, PanelBounds>()
        placeWithBars(root, "", PanelBounds(0f, 0f, maxWidth.value, maxHeight.value), fillResult, sizes, regions, bars)
        val slots = fillResult.slots.filter { !it.collapsed }
        for (slot in slots.sortedBy { if (it.panelIds.size > 1) 0 else 1 }) {
            val rects = slot.panelIds.mapNotNull { regions[it] }
            if (rects.isEmpty()) continue
            val box = unionOf(rects)
            var modifier = Modifier.offset(box.x.dp, box.y.dp).size(box.w.dp, box.h.dp)
            if (!fillsBox(box, rects)) modifier = modifier.clip(unionShape(rects, box))
            Box(modifier) {
                val id = if (keyPanelId != null && keyPanelId in slot.panelIds) keyPanelId else slot.panelIds.last()
                KeyedPanel(id, slot.holdsKeys(keyPanelId, keyLayer), onKey) { panel(slot) }
            }
        }
        for (bar in bars) SplitBar(bar, sizes)
    }
}

private data class SplitBar(
    val key: String,
    val beside: Boolean,
    val share: Float,
    val x: Float,
    val y: Float,
    val span: Float,
    val leading: Float,
    val cross: Float,
)

private fun placeWithBars(
    node: LayoutNode,
    key: String,
    rect: PanelBounds,
    fillResult: FillResult,
    sizes: Map<String, Float>,
    out: MutableMap<String, PanelBounds>,
    bars: MutableList<SplitBar>,
) {
    when (node) {
        is LayoutNode.Panel -> out[node.panel.id] = rect
        is LayoutNode.Split -> {
            if (node.first.collapsedIn(fillResult) && node.second.collapsedIn(fillResult)) return
            if (node.first.collapsedIn(fillResult)) {
                placeWithBars(node.second, key + "1", rect, fillResult, sizes, out, bars)
                return
            }
            if (node.second.collapsedIn(fillResult)) {
                placeWithBars(node.first, key + "0", rect, fillResult, sizes, out, bars)
                return
            }
            val beside = node.direction == SplitDirection.Beside
            val span = if (beside) rect.w else rect.h
            val cross = if (beside) rect.h else rect.w
            val insideCover = splitSitsInsideCover(node, fillResult)
            val gutter = if (insideCover) 0f else 8f
            val maxLeading = (span - gutter).coerceAtLeast(0f)
            val raw = if (insideCover) span * node.share else sizes[key] ?: ((span - gutter) * node.share)
            val leading = raw.coerceIn(0f, maxLeading)
            val trailingStart = leading + gutter
            val trailing = (span - trailingStart).coerceAtLeast(0f)
            if (!insideCover) {
                bars.add(SplitBar(key, beside, node.share, rect.x, rect.y, span, leading, cross))
            }
            if (beside) {
                placeWithBars(node.first, key + "0", PanelBounds(rect.x, rect.y, leading, rect.h), fillResult, sizes, out, bars)
                placeWithBars(
                    node.second,
                    key + "1",
                    PanelBounds(rect.x + trailingStart, rect.y, trailing, rect.h),
                    fillResult,
                    sizes,
                    out,
                    bars,
                )
            } else {
                placeWithBars(node.first, key + "0", PanelBounds(rect.x, rect.y, rect.w, leading), fillResult, sizes, out, bars)
                placeWithBars(
                    node.second,
                    key + "1",
                    PanelBounds(rect.x, rect.y + trailingStart, rect.w, trailing),
                    fillResult,
                    sizes,
                    out,
                    bars,
                )
            }
        }
    }
}

private fun unionOf(rects: List<PanelBounds>): PanelBounds {
    val left = rects.minOf { it.x }
    val top = rects.minOf { it.y }
    val right = rects.maxOf { it.x + it.w }
    val bottom = rects.maxOf { it.y + it.h }
    return PanelBounds(left, top, right - left, bottom - top)
}

private fun fillsBox(box: PanelBounds, rects: List<PanelBounds>): Boolean {
    val area = rects.sumOf { (it.w * it.h).toDouble() }
    return kotlin.math.abs(area - box.w * box.h) < 1.0
}

private fun unionShape(rects: List<PanelBounds>, box: PanelBounds): Shape = object : Shape {
    override fun createOutline(size: androidx.compose.ui.geometry.Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val path = Path()
        val scale = density.density
        for (rect in rects) {
            path.addRect(
                androidx.compose.ui.geometry.Rect(
                    (rect.x - box.x) * scale,
                    (rect.y - box.y) * scale,
                    (rect.x - box.x + rect.w) * scale,
                    (rect.y - box.y + rect.h) * scale,
                ),
            )
        }
        return Outline.Generic(path)
    }
}

@Composable
private fun SplitBar(bar: SplitBar, sizes: SnapshotStateMap<String, Float>) {
    val drag = rememberUpdatedState(bar)
    val density = LocalDensity.current
    val densityNow = rememberUpdatedState(density)
    val label = S("Resize pane")
    val grow = if (bar.beside) S("Wider") else S("Taller")
    val shrink = if (bar.beside) S("Narrower") else S("Shorter")
    val modifier = if (bar.beside) {
        Modifier
            .offset(x = bar.x.dp + bar.leading.dp, y = bar.y.dp)
            .width(8.dp)
            .height(bar.cross.dp)
    } else {
        Modifier
            .offset(x = bar.x.dp, y = bar.y.dp + bar.leading.dp)
            .height(8.dp)
            .width(bar.cross.dp)
    }
    Box(
        modifier
            .zIndex(1f)
            .background(MaterialTheme.colorScheme.outlineVariant)
            .pointerInput(bar.key, bar.beside) {
                val gesture: (Float) -> Unit = { dragPx ->
                    val current = drag.value
                    val delta = dragPx / densityNow.value.density
                    val max = (current.span - 8f).coerceAtLeast(0f)
                    val base = sizes[current.key] ?: ((current.span - 8f) * current.share)
                    sizes[current.key] = (base + delta).coerceIn(0f, max)
                }
                if (bar.beside) {
                    detectHorizontalDragGestures { _, amount -> gesture(amount) }
                } else {
                    detectVerticalDragGestures { _, amount -> gesture(amount) }
                }
            }
            .focusable()
            .semantics {
                contentDescription = label
                customActions = listOf(
                    CustomAccessibilityAction(grow) {
                        val current = drag.value
                        val max = (current.span - 8f).coerceAtLeast(0f)
                        val base = sizes[current.key] ?: ((current.span - 8f) * current.share)
                        sizes[current.key] = (base + 48f).coerceIn(0f, max)
                        true
                    },
                    CustomAccessibilityAction(shrink) {
                        val current = drag.value
                        val max = (current.span - 8f).coerceAtLeast(0f)
                        val base = sizes[current.key] ?: ((current.span - 8f) * current.share)
                        sizes[current.key] = (base - 48f).coerceIn(0f, max)
                        true
                    },
                )
            },
    )
}

private fun LayoutNode.collapsedIn(fillResult: FillResult): Boolean {
    val ids = panels().map { it.id }.toSet()
    if (ids.isEmpty()) return true
    return ids.all { id ->
        val slot = fillResult.slots.firstOrNull { id in it.panelIds } ?: return@all true
        slot.collapsed
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun KeyedPanel(id: String, holdsKeys: Boolean, onKey: (String) -> Unit, content: @Composable () -> Unit) {
    val requester = remember(id) { FocusRequester() }
    val focusManager = LocalFocusManager.current
    Box(
        Modifier
            .fillMaxSize()
            .focusRequester(requester)
            .focusRestorer()
            .focusTarget()
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
    LaunchedEffect(holdsKeys, id) {
        if (!holdsKeys) return@LaunchedEffect
        // The top layer can request focus as it appears. Take the keys back on the next frame.
        withFrameNanos { }
        runCatching {
            requester.requestFocus()
            focusManager.moveFocus(FocusDirection.Enter)
        }
    }
}
