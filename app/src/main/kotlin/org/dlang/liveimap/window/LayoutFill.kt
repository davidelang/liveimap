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
enum class LayoutRecordProblem { DuplicateLayer, NoUnpinnedPanel }

fun layoutRecordProblem(record: LayoutRecord): LayoutRecordProblem? {
    val pins = record.panels.mapNotNull { it.pin }
    if (pins.size != pins.toSet().size) return LayoutRecordProblem.DuplicateLayer
    val ids = record.panels.map { it.id }
    if (ids.size != ids.toSet().size) return LayoutRecordProblem.DuplicateLayer
    // The window stack needs somewhere to go.
    if (record.panels.isNotEmpty() && record.panels.all { it.pin != null }) return LayoutRecordProblem.NoUnpinnedPanel
    return null
}

/** The layer kinds this record binds to panels. Pins come only from the layout definition. */
fun pinnedKinds(record: LayoutRecord?): Set<WindowLayerKind> =
    record?.panels?.mapNotNull { it.pin }?.toSet().orEmpty()

data class FillSlot(val panelIds: List<String>, val layer: WindowLayer?, val collapsed: Boolean)

/**
 * [hiddenBelow]: unpinned stack layers under the visible window. [hiddenAbove]: unpinned layers above it
 * (the view was moved down the stack). [problem]: the record cannot be shown as defined; the window
 * reports it and does not rewrite the record.
 */
data class FillResult(
    val slots: List<FillSlot>,
    val hiddenBelow: Int = 0,
    val hiddenAbove: Int = 0,
    val problem: LayoutRecordProblem? = null,
)

/** Zoom remembers the fill it replaced. Back clears this before it pops a layer. */
data class ZoomState(val layer: WindowLayer, val unzoomed: FillResult)

data class BackStep(val dismissedDialog: Boolean, val zoom: ZoomState?, val stack: WindowStack)

/**
 * Fills [record] from [stack]. A null record, or a record with no panels, is an empty catalog:
 * one panel, the top layer. A dialog is not an argument here.
 *
 * [viewOffset] moves the visible window down the unpinned part of the stack by that many layers
 * (clamped). It only changes what is shown; the stack is untouched.
 */
fun fill(record: LayoutRecord?, stack: WindowStack, viewOffset: Int = 0): FillResult {
    if (record == null || record.panels.isEmpty()) {
        val layers = stack.layers
        val off = viewOffset.coerceIn(0, layers.size - 1)
        return FillResult(
            listOf(FillSlot(listOf("panel"), layers[layers.size - 1 - off], collapsed = false)),
            hiddenBelow = layers.size - 1 - off,
            hiddenAbove = off,
        )
    }
    val panels = record.panels
    val count = panels.size
    val coverCount = record.coverPanels.coerceIn(0, count)
    val covered = BooleanArray(count)
    for (index in (count - coverCount) until count) covered[index] = true
    val assigned = arrayOfNulls<WindowLayer>(count)
    val used = HashSet<WindowLayer>()
    // A pin binds the panel to a layer kind. No layer of that kind: the panel stays empty, and nothing flows in.
    // With the view at the top, a cover takes the top layer first (pins then use the next layer of their kind).
    val coverFirst = coverCount > 0 && viewOffset <= 0
    if (coverFirst) used.add(stack.layers.last())
    for (index in 0 until count) {
        if (covered[index]) continue
        val pin = panels[index].pin ?: continue
        val layer = stack.layers.lastOrNull { it.kind == pin && it !in used } ?: continue
        assigned[index] = layer
        used.add(layer)
    }
    if (coverFirst) used.remove(stack.layers.last())
    // The dynamic stack flows only through the unpinned panels; pinned layers neither show there nor count.
    val flow = stack.layers.filter { it !in used }
    val free = (0 until count).count { !covered[it] && panels[it].pin == null }
    val capacity = (if (coverCount > 0) 1 else 0) + minOf(free, record.unpinnedKeep.coerceAtLeast(0))
    val offset = viewOffset.coerceIn(0, maxOf(0, flow.size - capacity))
    val window = flow.dropLast(offset)
    val coverLayer = if (coverCount > 0) window.lastOrNull() else null
    val rest = if (coverLayer != null) window.dropLast(1) else window
    val queue = ArrayDeque(rest.takeLast(minOf(free, record.unpinnedKeep.coerceAtLeast(0))).asReversed())
    val shownFromFlow = (if (coverLayer != null) 1 else 0) + queue.size
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
            slots.add(FillSlot(ids, coverLayer, collapsed = false))
            break
        }
        val layer = assigned[index]
        slots.add(
            FillSlot(
                panelIds = listOf(panels[index].id),
                layer = layer,
                // An empty pinned panel stays in place (empty), so the unpinned panels do not shift.
                collapsed = layer == null && record.unused == UnusedPanel.Collapse && panels[index].pin == null,
            ),
        )
        index += 1
    }
    val seen = HashSet<WindowLayer>()
    for (slot in slots) {
        val layer = slot.layer ?: continue
        require(seen.add(layer))
    }
    return FillResult(
        slots,
        hiddenBelow = window.size - shownFromFlow,
        hiddenAbove = offset,
        problem = if (free == 0 && coverCount == 0) LayoutRecordProblem.NoUnpinnedPanel else null,
    )
}

/** Which stack arrows the window shows. None when every stack layer is visible. */
data class StackArrows(val down: Boolean, val up: Boolean)

fun stackArrows(shown: FillResult): StackArrows = StackArrows(down = shown.hiddenBelow > 0, up = shown.hiddenAbove > 0)

/**
 * The view position over a stack. It belongs to [stack]: any push, pop or pick makes a new stack,
 * and the view goes back to the top.
 */
data class StackView(val stack: WindowStack, val offset: Int)

fun viewOffset(view: StackView?, stack: WindowStack): Int = if (view?.stack == stack) view.offset else 0

/** An arrow moves the view one layer; [down] shows a lower layer. The new offset is what fill shows. */
fun stackViewStep(record: LayoutRecord?, view: StackView?, stack: WindowStack, down: Boolean): StackView {
    val now = fill(record, stack, viewOffset(view, stack))
    val wanted = now.hiddenAbove + if (down) 1 else -1
    return StackView(stack, fill(record, stack, wanted.coerceAtLeast(0)).hiddenAbove)
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
fun displayedFill(record: LayoutRecord?, stack: WindowStack, zoom: ZoomState?, viewOffset: Int = 0): FillResult {
    val base = fill(record, stack, viewOffset)
    if (zoom == null || drawerOverlayOpen(zoom)) return base
    // A screen pushed above a zoom is what the user asked for; the zoom must not hide it.
    val top = stack.layers.last()
    if (top.kind == WindowLayerKind.Screen && zoom.layer != top) return base
    return zoomFill(zoom)
}

/** Opening screen [key] keeps a zoom only when that zoom is this screen. */
fun zoomAfterScreenOpen(zoom: ZoomState?, key: String): ZoomState? =
    zoom?.takeIf { it.layer == WindowLayer(WindowLayerKind.Screen, key) }

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
