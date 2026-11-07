package org.dlang.liveimap.window

/** An unused panel either collapses or stays on screen with nothing in it. */
enum class UnusedPanel { Collapse, StayEmpty }

/**
 * One panel, from the unfocused end toward the focused end.
 * [pin] keeps that layer in this panel when the unpinned keep-count would have left it off.
 */
data class LayoutPanel(val id: String, val pin: WindowLayerKind? = null)

/** Beside is side by side. Above is the first child on top. [share] is the first child's fraction. */
enum class SplitDirection { Beside, Above }

sealed class LayoutNode {
    data class Panel(val panel: LayoutPanel) : LayoutNode()
    data class Split(
        val direction: SplitDirection,
        val share: Float,
        val first: LayoutNode,
        val second: LayoutNode,
    ) : LayoutNode()
}

fun LayoutNode.panels(): List<LayoutPanel> = when (this) {
    is LayoutNode.Panel -> listOf(panel)
    is LayoutNode.Split -> first.panels() + second.panels()
}

/**
 * How one window fills. [root] is the split geometry; a null root is an empty panel list.
 * [coverPanels] is how many panels the top layer occupies, taken from the focused end as one span.
 * [unpinnedKeep] is how many layers, from the top, the unpinned panels may show.
 * Dragged divider positions are not stored here.
 */
data class LayoutRecord(
    val root: LayoutNode?,
    val unused: UnusedPanel,
    val coverPanels: Int,
    val unpinnedKeep: Int,
) {
    val panels: List<LayoutPanel> get() = root?.panels().orEmpty()
}

/** Two panels pinned to one layer kind, or two panels with one id. A message with no index panel is allowed. */
enum class LayoutRecordProblem { DuplicateLayer }

fun layoutRecordProblem(record: LayoutRecord): LayoutRecordProblem? {
    val pins = record.panels.mapNotNull { it.pin }
    if (pins.size != pins.toSet().size) return LayoutRecordProblem.DuplicateLayer
    val ids = record.panels.map { it.id }
    if (ids.size != ids.toSet().size) return LayoutRecordProblem.DuplicateLayer
    return null
}

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

/** A drawer zoom is drawn over the fill as a modal sheet, not in place of the fill. */
fun drawerOverlayOpen(zoom: ZoomState?): Boolean = zoom?.layer?.kind == WindowLayerKind.Drawer

/**
 * The hamburger. An open drawer closes. A fill that already shows the drawer in a panel keeps [zoom].
 * Otherwise the drawer opens over [base].
 */
fun toggleDrawer(base: FillResult, drawer: WindowLayer, zoom: ZoomState?): ZoomState? {
    require(drawer.kind == WindowLayerKind.Drawer)
    if (drawerOverlayOpen(zoom)) return null
    if (base.slots.any { !it.collapsed && it.layer == drawer }) return zoom
    return ZoomState(drawer, base)
}

/**
 * Where the hamburger goes. [slot] is the index in [shown] of the first visible panel; that bar carries it.
 * None when a panel of [base] already shows the drawer. [overOpenDrawer]: an open drawer overlay keeps a
 * copy on the same spot, drawn above the sheet, so a second tap closes it.
 */
data class HamburgerPlan(val slot: Int?, val overOpenDrawer: Boolean)

fun hamburgerPlan(base: FillResult, shown: FillResult, zoom: ZoomState?): HamburgerPlan {
    val inPanel = base.slots.any { !it.collapsed && it.layer?.kind == WindowLayerKind.Drawer }
    if (inPanel && !drawerOverlayOpen(zoom)) return HamburgerPlan(slot = null, overOpenDrawer = false)
    val first = shown.slots.indexOfFirst { !it.collapsed && it.layer != null }
    return HamburgerPlan(slot = first.takeIf { it >= 0 }, overOpenDrawer = drawerOverlayOpen(zoom))
}

/** What the window draws. The drawer overlay leaves the fill under it; any other zoom replaces it. */
fun displayedFill(record: LayoutRecord?, stack: WindowStack, zoom: ZoomState?): FillResult {
    val base = fill(record, stack)
    if (zoom == null || drawerOverlayOpen(zoom)) return base
    return zoomFill(zoom)
}

/**
 * Zoom of the one panel that is already the whole window does nothing.
 * A pin does not block zoom: more than one visible slot still zooms.
 */
fun zoomTarget(current: FillResult, layer: WindowLayer): ZoomState? {
    val shown = current.slots.filter { !it.collapsed && it.layer != null }
    if (shown.size == 1 && shown[0].layer == layer) return null
    return zoom(current, layer)
}

/**
 * The panel that has the keys. [requested] stays when that panel is still on screen,
 * even when it is not the top of the stack. Otherwise the focused end.
 */
fun keyPanel(fill: FillResult, requested: String?): String? {
    val ids = fill.slots.filter { !it.collapsed }.flatMap { it.panelIds }
    if (ids.isEmpty()) return null
    if (requested != null && requested in ids) return requested
    return ids.last()
}

fun keyLayer(fill: FillResult, panelId: String?): WindowLayer? {
    if (panelId == null) return null
    return fill.slots.firstOrNull { panelId in it.panelIds }?.layer
}

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
