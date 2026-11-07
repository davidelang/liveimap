package org.dlang.liveimap.window

/**
 * The drawer is always the bottom layer of the window stack. [peek] is the drawer slid back over the
 * panels for a moment; it changes nothing in [stack].
 */
data class DrawerView(val stack: WindowStack, val peek: Boolean)

sealed interface DrawerInput {
    /** The menu button. */
    data object Menu : DrawerInput
    /** A scrim tap or a swipe on the peek. */
    data object Hide : DrawerInput
    /** A drawer item, from the peek or from a panel that shows the drawer. [above] replaces every layer above it. */
    data class Pick(val above: List<WindowLayer>) : DrawerInput
    data object Back : DrawerInput
}

/**
 * Peek versus stack. Menu and Hide only show or hide the peek. A pick keeps the drawer and replaces the rest.
 * Back hides a peek first, then pops one layer. [drawerInPanel]: a panel already shows the drawer, so Menu does nothing.
 */
fun drawerStep(view: DrawerView, input: DrawerInput, drawerInPanel: Boolean): DrawerView = when (input) {
    DrawerInput.Menu -> if (drawerInPanel) view.copy(peek = false) else view.copy(peek = !view.peek)
    DrawerInput.Hide -> view.copy(peek = false)
    is DrawerInput.Pick -> DrawerView(WindowStack(listOf(view.stack.layers.first()) + input.above), peek = false)
    DrawerInput.Back -> if (view.peek) view.copy(peek = false) else view.copy(stack = view.stack.pop())
}

/** Whether a visible panel of [shown] holds the drawer layer. */
fun drawerInPanel(shown: FillResult): Boolean =
    shown.slots.any { !it.collapsed && it.layer?.kind == WindowLayerKind.Drawer }

/**
 * Generic: Back leaves the window when there is nothing to pop, or when the pop would leave only layers that
 * pinned panels show (the unpinned panels would hold nothing from the stack). A pinned layer is never popped.
 */
fun backLeavesWindow(stack: WindowStack, pinned: Set<WindowLayerKind>): Boolean {
    if (stack.layers.size == 1) return true
    return stack.pop().layers.all { it.kind in pinned }
}
