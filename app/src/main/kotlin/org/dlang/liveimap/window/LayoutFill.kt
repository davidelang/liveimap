package org.dlang.liveimap.window

/** An unused panel either collapses or stays on screen with nothing in it. */
enum class UnusedPanel { Collapse, StayEmpty }

/**
 * One panel, from the unfocused end toward the focused end.
 * [pin] keeps that layer in this panel when the unpinned keep-count would have left it off.
 */
data class LayoutPanel(val id: String, val pin: WindowLayerKind? = null)

/**
 * How one window fills. Not stored by this change.
 * [coverPanels] is how many panels the top layer occupies, taken from the focused end as one span.
 * [unpinnedKeep] is how many layers, from the top, the unpinned panels may show.
 */
data class LayoutRecord(
    val panels: List<LayoutPanel>,
    val unused: UnusedPanel,
    val coverPanels: Int,
    val unpinnedKeep: Int,
)

data class FillSlot(val panelIds: List<String>, val layer: WindowLayer?, val collapsed: Boolean)

data class FillResult(val slots: List<FillSlot>)

/** Zoom remembers the fill it replaced. Back clears this before it pops a layer. */
data class ZoomState(val layer: WindowLayer, val unzoomed: FillResult)

data class BackStep(val dismissedDialog: Boolean, val zoom: ZoomState?, val stack: WindowStack)

/**
 * Fills [record] from [stack]. A null record, or a record with no panels, is an empty catalog:
 * one panel, the top layer. A dialog is not an argument here.
 */
fun fill(record: LayoutRecord?, stack: WindowStack): FillResult {
    val top = stack.layers.last()
    if (record == null || record.panels.isEmpty()) {
        return FillResult(listOf(FillSlot(listOf("panel"), top, collapsed = false)))
    }
    val panels = record.panels
    val count = panels.size
    val coverCount = record.coverPanels.coerceIn(0, count)
    val used = HashSet<WindowLayer>()
    val covered = BooleanArray(count)
    if (coverCount > 0) {
        for (index in (count - coverCount) until count) covered[index] = true
        used.add(top)
    }
    val assigned = arrayOfNulls<WindowLayer>(count)
    for (index in 0 until count) {
        if (covered[index]) continue
        val pin = panels[index].pin ?: continue
        val layer = stack.layers.lastOrNull { it.kind == pin && it !in used } ?: continue
        assigned[index] = layer
        used.add(layer)
    }
    val queue = ArrayDeque(
        stack.layers.takeLast(record.unpinnedKeep.coerceAtLeast(0)).asReversed().filter { it !in used },
    )
    for (index in (count - 1) downTo 0) {
        if (covered[index] || panels[index].pin != null || queue.isEmpty()) continue
        assigned[index] = queue.removeFirst()
    }
    val slots = ArrayList<FillSlot>(count)
    var index = 0
    while (index < count) {
        if (covered[index]) {
            val ids = ArrayList<String>(coverCount)
            for (coveredIndex in index until count) ids.add(panels[coveredIndex].id)
            slots.add(FillSlot(ids, top, collapsed = false))
            break
        }
        val layer = assigned[index]
        slots.add(
            FillSlot(
                panelIds = listOf(panels[index].id),
                layer = layer,
                collapsed = layer == null && record.unused == UnusedPanel.Collapse,
            ),
        )
        index += 1
    }
    val seen = HashSet<WindowLayer>()
    for (slot in slots) {
        val layer = slot.layer ?: continue
        require(seen.add(layer))
    }
    return FillResult(slots)
}

fun zoom(current: FillResult, layer: WindowLayer): ZoomState = ZoomState(layer, current)

/** One panel, the zoomed layer. The remembered fill is left on [ZoomState.unzoomed]. */
fun zoomFill(zoom: ZoomState): FillResult =
    FillResult(listOf(FillSlot(listOf("zoom"), zoom.layer, collapsed = false)))

/**
 * A dialog closes first and is not a layer. Zoom clears next. Only then does the top layer pop.
 * The drawer does not pop.
 */
fun back(dialogOpen: Boolean, zoom: ZoomState?, stack: WindowStack): BackStep {
    if (dialogOpen) return BackStep(dismissedDialog = true, zoom = zoom, stack = stack)
    if (zoom != null) return BackStep(dismissedDialog = false, zoom = null, stack = stack)
    return BackStep(dismissedDialog = false, zoom = null, stack = stack.pop())
}
