package org.dlang.liveimap.ui.toolbar

import org.dlang.liveimap.settings.AccountSettings

enum class IndexBarAction {
    Refresh,
    Search,
    Filter,
}

enum class BarSection {
    Toolbar,
    Overflow,
    Hidden,
}

private sealed interface DragLine<A> {
    class Header<A>(val section: BarSection) : DragLine<A>
    class Item<A>(val action: A) : DragLine<A>
}

fun <A> dragActions(
    toolbar: List<A>,
    overflow: List<A>,
    hidden: List<A>,
    from: Int,
    to: Int,
): Triple<List<A>, List<A>, List<A>> {
    val lines = ArrayList<DragLine<A>>(toolbar.size + overflow.size + hidden.size + 3)
    lines.add(DragLine.Header(BarSection.Toolbar))
    for (action in toolbar) lines.add(DragLine.Item(action))
    lines.add(DragLine.Header(BarSection.Overflow))
    for (action in overflow) lines.add(DragLine.Item(action))
    lines.add(DragLine.Header(BarSection.Hidden))
    for (action in hidden) lines.add(DragLine.Item(action))
    if (from !in lines.indices || to !in lines.indices || from == to || lines[from] is DragLine.Header<*>) {
        return Triple(toolbar, overflow, hidden)
    }
    val moved = lines.toMutableList()
    val item = moved.removeAt(from)
    moved.add(to.coerceIn(1, moved.size), item)
    val nextToolbar = ArrayList<A>()
    val nextOverflow = ArrayList<A>()
    val nextHidden = ArrayList<A>()
    var section = BarSection.Toolbar
    for (line in moved) {
        when (line) {
            is DragLine.Header -> section = line.section
            is DragLine.Item -> when (section) {
                BarSection.Toolbar -> nextToolbar.add(line.action)
                BarSection.Overflow -> nextOverflow.add(line.action)
                BarSection.Hidden -> nextHidden.add(line.action)
            }
        }
    }
    return Triple(nextToolbar, nextOverflow, nextHidden)
}

fun <A> dragLanding(
    toolbar: List<A>,
    overflow: List<A>,
    hidden: List<A>,
    from: Int,
    to: Int,
): Pair<A, BarSection>? {
    val (nextToolbar, nextOverflow, nextHidden) = dragActions(toolbar, overflow, hidden, from, to)
    if (nextToolbar == toolbar && nextOverflow == overflow && nextHidden == hidden) return null
    val lines = ArrayList<DragLine<A>>(toolbar.size + overflow.size + hidden.size + 3)
    lines.add(DragLine.Header(BarSection.Toolbar))
    for (action in toolbar) lines.add(DragLine.Item(action))
    lines.add(DragLine.Header(BarSection.Overflow))
    for (action in overflow) lines.add(DragLine.Item(action))
    lines.add(DragLine.Header(BarSection.Hidden))
    for (action in hidden) lines.add(DragLine.Item(action))
    val moved = when (val line = lines.getOrNull(from)) {
        is DragLine.Item -> line.action
        else -> return null
    }
    val section = when {
        moved in nextToolbar -> BarSection.Toolbar
        moved in nextOverflow -> BarSection.Overflow
        moved in nextHidden -> BarSection.Hidden
        else -> return null
    }
    return moved to section
}

private fun <A> moveSection(
    toolbar: List<A>,
    overflow: List<A>,
    hidden: List<A>,
    action: A,
    section: BarSection,
): Triple<List<A>, List<A>, List<A>>? {
    val current = when {
        action in toolbar -> BarSection.Toolbar
        action in overflow -> BarSection.Overflow
        action in hidden -> BarSection.Hidden
        else -> return null
    }
    if (current == section) return Triple(toolbar, overflow, hidden)
    val nextToolbar = toolbar.filterNot { it == action }
    val nextOverflow = overflow.filterNot { it == action }
    val nextHidden = hidden.filterNot { it == action }
    return when (section) {
        BarSection.Toolbar -> Triple(nextToolbar + action, nextOverflow, nextHidden)
        BarSection.Overflow -> Triple(nextToolbar, nextOverflow + action, nextHidden)
        BarSection.Hidden -> Triple(nextToolbar, nextOverflow, nextHidden + action)
    }
}

private fun <A> moveByDelta(
    toolbar: List<A>,
    overflow: List<A>,
    hidden: List<A>,
    action: A,
    delta: Int,
): Triple<List<A>, List<A>, List<A>>? {
    if (delta != -1 && delta != 1) return Triple(toolbar, overflow, hidden)
    val section = when {
        action in toolbar -> BarSection.Toolbar
        action in overflow -> BarSection.Overflow
        action in hidden -> BarSection.Hidden
        else -> return null
    }
    val list = when (section) {
        BarSection.Toolbar -> toolbar
        BarSection.Overflow -> overflow
        BarSection.Hidden -> hidden
    }
    val index = list.indexOf(action)
    if (index < 0) return null
    val target = index + delta
    if (target !in list.indices) return Triple(toolbar, overflow, hidden)
    val next = list.toMutableList()
    next.removeAt(index)
    next.add(target, action)
    return when (section) {
        BarSection.Toolbar -> Triple(next, overflow, hidden)
        BarSection.Overflow -> Triple(toolbar, next, hidden)
        BarSection.Hidden -> Triple(toolbar, overflow, next)
    }
}

private fun <A, L> applyMove(
    layout: L,
    toolbar: List<A>,
    overflow: List<A>,
    hidden: List<A>,
    moved: Triple<List<A>, List<A>, List<A>>?,
    build: (List<A>, List<A>, List<A>) -> L,
): L {
    if (moved == null) return layout
    if (moved.first === toolbar && moved.second === overflow && moved.third === hidden) return layout
    return build(moved.first, moved.second, moved.third)
}

data class IndexBarLayout(
    val toolbar: List<IndexBarAction>,
    val overflow: List<IndexBarAction>,
    val hidden: List<IndexBarAction>,
)

sealed interface IndexMenuEntry {
    data object Divider : IndexMenuEntry
    data class Action(val action: IndexBarAction) : IndexMenuEntry
    data object Customize : IndexMenuEntry
}

fun defaultIndexBar(): IndexBarLayout = IndexBarLayout(
    toolbar = listOf(IndexBarAction.Search),
    overflow = listOf(IndexBarAction.Refresh, IndexBarAction.Filter),
    hidden = emptyList(),
)

fun resetIndexBar(): IndexBarLayout = defaultIndexBar()

fun moveIndexAction(layout: IndexBarLayout, action: IndexBarAction, section: BarSection): IndexBarLayout =
    applyMove(layout, layout.toolbar, layout.overflow, layout.hidden, moveSection(layout.toolbar, layout.overflow, layout.hidden, action, section)) { toolbar, overflow, hidden ->
        IndexBarLayout(toolbar, overflow, hidden)
    }

fun moveIndexActionBy(layout: IndexBarLayout, action: IndexBarAction, delta: Int): IndexBarLayout =
    applyMove(layout, layout.toolbar, layout.overflow, layout.hidden, moveByDelta(layout.toolbar, layout.overflow, layout.hidden, action, delta)) { toolbar, overflow, hidden ->
        IndexBarLayout(toolbar, overflow, hidden)
    }

fun dragIndexLayout(layout: IndexBarLayout, from: Int, to: Int): IndexBarLayout {
    val (toolbar, overflow, hidden) = dragActions(layout.toolbar, layout.overflow, layout.hidden, from, to)
    return IndexBarLayout(toolbar, overflow, hidden)
}

fun indexMenuTail(layout: IndexBarLayout): List<IndexMenuEntry> {
    val items = ArrayList<IndexMenuEntry>()
    items.add(IndexMenuEntry.Divider)
    if (layout.overflow.isNotEmpty()) {
        for (action in layout.overflow) items.add(IndexMenuEntry.Action(action))
        items.add(IndexMenuEntry.Divider)
    }
    items.add(IndexMenuEntry.Customize)
    return items
}

fun encodeIndexBar(layout: IndexBarLayout): String =
    "T:${layout.toolbar.joinToString(",") { it.name }}" +
        "|O:${layout.overflow.joinToString(",") { it.name }}" +
        "|H:${layout.hidden.joinToString(",") { it.name }}"

fun parseIndexBar(value: String): IndexBarLayout {
    val match = indexBarShape.matchEntire(value) ?: throw IllegalArgumentException("bad indexBar")
    val toolbar = parseIndexNames(match.groupValues[1])
    val overflow = parseIndexNames(match.groupValues[2])
    val hidden = parseIndexNames(match.groupValues[3])
    // F69: tolerant: duplicates keep their first place; actions this build added go where the default puts them
    val seen = HashSet<IndexBarAction>()
    val t = toolbar.filter { seen.add(it) }
    val o = overflow.filter { seen.add(it) }
    val h = hidden.filter { seen.add(it) }
    val base = defaultIndexBar()
    val missing = IndexBarAction.entries.filter { it !in seen }
    return IndexBarLayout(
        t + missing.filter { it in base.toolbar },
        o + missing.filter { it in base.overflow || (it !in base.toolbar && it !in base.hidden) },
        h + missing.filter { it in base.hidden },
    )
}

private val indexBarShape = Regex(
    "^T:([A-Za-z]+(?:,[A-Za-z]+)*)?\\|O:([A-Za-z]+(?:,[A-Za-z]+)*)?\\|H:([A-Za-z]+(?:,[A-Za-z]+)*)?$",
)

private fun parseIndexNames(text: String): List<IndexBarAction> {
    if (text.isEmpty()) return emptyList()
    return text.split(',').mapNotNull { name ->
        IndexBarAction.entries.firstOrNull { it.name == name } // F69: an unknown (newer) action is dropped
    }
}

enum class SelectionBarAction {
    Seen,
    Flag,
    Move,
    Delete,
}

data class SelectionBarLayout(
    val toolbar: List<SelectionBarAction>,
    val overflow: List<SelectionBarAction>,
    val hidden: List<SelectionBarAction>,
)

sealed interface SelectionMenuEntry {
    data object Divider : SelectionMenuEntry
    data class Action(val action: SelectionBarAction) : SelectionMenuEntry
    data object Customize : SelectionMenuEntry
}

fun defaultSelectionBar(): SelectionBarLayout = SelectionBarLayout(
    toolbar = listOf(SelectionBarAction.Delete),
    overflow = listOf(
        SelectionBarAction.Seen,
        SelectionBarAction.Flag,
        SelectionBarAction.Move,
    ),
    hidden = emptyList(),
)

fun resetSelectionBar(): SelectionBarLayout = defaultSelectionBar()

fun moveSelectionAction(
    layout: SelectionBarLayout,
    action: SelectionBarAction,
    section: BarSection,
): SelectionBarLayout =
    applyMove(layout, layout.toolbar, layout.overflow, layout.hidden, moveSection(layout.toolbar, layout.overflow, layout.hidden, action, section)) { toolbar, overflow, hidden ->
        SelectionBarLayout(toolbar, overflow, hidden)
    }

fun moveSelectionActionBy(layout: SelectionBarLayout, action: SelectionBarAction, delta: Int): SelectionBarLayout =
    applyMove(layout, layout.toolbar, layout.overflow, layout.hidden, moveByDelta(layout.toolbar, layout.overflow, layout.hidden, action, delta)) { toolbar, overflow, hidden ->
        SelectionBarLayout(toolbar, overflow, hidden)
    }

fun dragSelectionLayout(layout: SelectionBarLayout, from: Int, to: Int): SelectionBarLayout {
    val (toolbar, overflow, hidden) = dragActions(layout.toolbar, layout.overflow, layout.hidden, from, to)
    return SelectionBarLayout(toolbar, overflow, hidden)
}

fun selectionMenuTail(layout: SelectionBarLayout): List<SelectionMenuEntry> {
    val items = ArrayList<SelectionMenuEntry>()
    items.add(SelectionMenuEntry.Divider)
    if (layout.overflow.isNotEmpty()) {
        for (action in layout.overflow) items.add(SelectionMenuEntry.Action(action))
        items.add(SelectionMenuEntry.Divider)
    }
    items.add(SelectionMenuEntry.Customize)
    return items
}

fun encodeSelectionBar(layout: SelectionBarLayout): String =
    "T:${layout.toolbar.joinToString(",") { it.name }}" +
        "|O:${layout.overflow.joinToString(",") { it.name }}" +
        "|H:${layout.hidden.joinToString(",") { it.name }}"

fun parseSelectionBar(value: String): SelectionBarLayout {
    val match = selectionBarShape.matchEntire(value) ?: throw IllegalArgumentException("bad selectionBar")
    val toolbar = parseSelectionNames(match.groupValues[1])
    val overflow = parseSelectionNames(match.groupValues[2])
    val hidden = parseSelectionNames(match.groupValues[3])
    // F69: tolerant: duplicates keep their first place; actions this build added go where the default puts them
    val seen = HashSet<SelectionBarAction>()
    val t = toolbar.filter { seen.add(it) }
    val o = overflow.filter { seen.add(it) }
    val h = hidden.filter { seen.add(it) }
    val base = defaultSelectionBar()
    val missing = SelectionBarAction.entries.filter { it !in seen }
    return SelectionBarLayout(
        t + missing.filter { it in base.toolbar },
        o + missing.filter { it in base.overflow || (it !in base.toolbar && it !in base.hidden) },
        h + missing.filter { it in base.hidden },
    )
}

private val selectionBarShape = Regex(
    "^T:([A-Za-z]+(?:,[A-Za-z]+)*)?\\|O:([A-Za-z]+(?:,[A-Za-z]+)*)?\\|H:([A-Za-z]+(?:,[A-Za-z]+)*)?$",
)

private fun parseSelectionNames(text: String): List<SelectionBarAction> {
    if (text.isEmpty()) return emptyList()
    return text.split(',').mapNotNull { name ->
        SelectionBarAction.entries.firstOrNull { it.name == name } // F69: an unknown (newer) action is dropped
    }
}

enum class FolderBarAction {
    Refresh,
    CollapseAll,
    SaveDefault,
    ResetDefault,
}

data class FolderBarLayout(
    val toolbar: List<FolderBarAction>,
    val overflow: List<FolderBarAction>,
    val hidden: List<FolderBarAction>,
)

sealed interface FolderMenuEntry {
    data class Action(val action: FolderBarAction) : FolderMenuEntry
    data object Unsent : FolderMenuEntry
    data object Divider : FolderMenuEntry
    data object Customize : FolderMenuEntry
}

fun defaultFolderBar(): FolderBarLayout = FolderBarLayout(
    toolbar = listOf(FolderBarAction.Refresh),
    overflow = listOf(
        FolderBarAction.CollapseAll,
        FolderBarAction.SaveDefault,
        FolderBarAction.ResetDefault,
    ),
    hidden = emptyList(),
)

fun resetFolderBar(): FolderBarLayout = defaultFolderBar()

fun moveFolderAction(
    layout: FolderBarLayout,
    action: FolderBarAction,
    section: BarSection,
): FolderBarLayout =
    applyMove(layout, layout.toolbar, layout.overflow, layout.hidden, moveSection(layout.toolbar, layout.overflow, layout.hidden, action, section)) { toolbar, overflow, hidden ->
        FolderBarLayout(toolbar, overflow, hidden)
    }

fun moveFolderActionBy(layout: FolderBarLayout, action: FolderBarAction, delta: Int): FolderBarLayout =
    applyMove(layout, layout.toolbar, layout.overflow, layout.hidden, moveByDelta(layout.toolbar, layout.overflow, layout.hidden, action, delta)) { toolbar, overflow, hidden ->
        FolderBarLayout(toolbar, overflow, hidden)
    }

fun dragFolderLayout(layout: FolderBarLayout, from: Int, to: Int): FolderBarLayout {
    val (toolbar, overflow, hidden) = dragActions(layout.toolbar, layout.overflow, layout.hidden, from, to)
    return FolderBarLayout(toolbar, overflow, hidden)
}

fun folderMenu(layout: FolderBarLayout, showUnsent: Boolean): List<FolderMenuEntry> {
    val items = ArrayList<FolderMenuEntry>()
    for (action in layout.overflow) items.add(FolderMenuEntry.Action(action))
    if (showUnsent) items.add(FolderMenuEntry.Unsent)
    items.add(FolderMenuEntry.Divider)
    items.add(FolderMenuEntry.Customize)
    return items
}

fun encodeFolderBar(layout: FolderBarLayout): String =
    "T:${layout.toolbar.joinToString(",") { it.name }}" +
        "|O:${layout.overflow.joinToString(",") { it.name }}" +
        "|H:${layout.hidden.joinToString(",") { it.name }}"

fun parseFolderBar(value: String): FolderBarLayout {
    val match = folderBarShape.matchEntire(value) ?: throw IllegalArgumentException("bad folderBar")
    val toolbar = parseFolderNames(match.groupValues[1])
    val overflow = parseFolderNames(match.groupValues[2])
    val hidden = parseFolderNames(match.groupValues[3])
    // F69: tolerant: duplicates keep their first place; actions this build added go where the default puts them
    val seen = HashSet<FolderBarAction>()
    val t = toolbar.filter { seen.add(it) }
    val o = overflow.filter { seen.add(it) }
    val h = hidden.filter { seen.add(it) }
    val base = defaultFolderBar()
    val missing = FolderBarAction.entries.filter { it !in seen }
    return FolderBarLayout(
        t + missing.filter { it in base.toolbar },
        o + missing.filter { it in base.overflow || (it !in base.toolbar && it !in base.hidden) },
        h + missing.filter { it in base.hidden },
    )
}

private val folderBarShape = Regex(
    "^T:([A-Za-z]+(?:,[A-Za-z]+)*)?\\|O:([A-Za-z]+(?:,[A-Za-z]+)*)?\\|H:([A-Za-z]+(?:,[A-Za-z]+)*)?$",
)

private fun parseFolderNames(text: String): List<FolderBarAction> {
    if (text.isEmpty()) return emptyList()
    return text.split(',').mapNotNull { name ->
        FolderBarAction.entries.firstOrNull { it.name == name } // F69: an unknown (newer) action is dropped
    }
}

enum class ReaderToolbarAction {
    Refresh,
    Reply,
    ReplyAll,
    Forward,
    Delete,
    Move,
    Spam,
    Bounce,
    Headers,
}

data class ReaderToolbarLayout(
    val toolbar: List<ReaderToolbarAction>,
    val overflow: List<ReaderToolbarAction>,
    val hidden: List<ReaderToolbarAction>,
)

fun defaultReaderToolbar(): ReaderToolbarLayout = ReaderToolbarLayout(
    toolbar = listOf(ReaderToolbarAction.Reply, ReaderToolbarAction.Delete),
    overflow = listOf(
        ReaderToolbarAction.Refresh,
        ReaderToolbarAction.ReplyAll,
        ReaderToolbarAction.Forward,
        ReaderToolbarAction.Move,
        ReaderToolbarAction.Spam,
        ReaderToolbarAction.Bounce,
        ReaderToolbarAction.Headers,
    ),
    hidden = emptyList(),
)

fun resetReaderToolbar(): ReaderToolbarLayout = defaultReaderToolbar()

fun effectiveReaderToolbar(settings: AccountSettings): ReaderToolbarLayout =
    settings.readerToolbar ?: defaultReaderToolbar()

fun visibleReaderActions(
    actions: List<ReaderToolbarAction>,
    spamMailbox: String,
): List<ReaderToolbarAction> =
    if (spamMailbox.isEmpty()) {
        actions.filter { it != ReaderToolbarAction.Spam }
    } else {
        actions
    }

fun moveReaderAction(
    layout: ReaderToolbarLayout,
    action: ReaderToolbarAction,
    section: BarSection,
): ReaderToolbarLayout =
    applyMove(layout, layout.toolbar, layout.overflow, layout.hidden, moveSection(layout.toolbar, layout.overflow, layout.hidden, action, section)) { toolbar, overflow, hidden ->
        ReaderToolbarLayout(toolbar, overflow, hidden)
    }

fun moveReaderActionBy(
    layout: ReaderToolbarLayout,
    action: ReaderToolbarAction,
    delta: Int,
): ReaderToolbarLayout =
    applyMove(layout, layout.toolbar, layout.overflow, layout.hidden, moveByDelta(layout.toolbar, layout.overflow, layout.hidden, action, delta)) { toolbar, overflow, hidden ->
        ReaderToolbarLayout(toolbar, overflow, hidden)
    }

fun dragReaderLayout(layout: ReaderToolbarLayout, from: Int, to: Int): ReaderToolbarLayout {
    val (toolbar, overflow, hidden) = dragActions(layout.toolbar, layout.overflow, layout.hidden, from, to)
    return ReaderToolbarLayout(toolbar, overflow, hidden)
}

fun encodeReaderToolbar(layout: ReaderToolbarLayout): String =
    "T:${layout.toolbar.joinToString(",") { it.name }}" +
        "|O:${layout.overflow.joinToString(",") { it.name }}" +
        "|H:${layout.hidden.joinToString(",") { it.name }}"

fun parseReaderToolbar(value: String): ReaderToolbarLayout {
    val match = readerToolbarShape.matchEntire(value) ?: throw IllegalArgumentException("bad readerToolbar")
    val toolbar = parseReaderToolbarNames(match.groupValues[1])
    val overflow = parseReaderToolbarNames(match.groupValues[2])
    val hidden = parseReaderToolbarNames(match.groupValues[3])
    // F69: tolerant: duplicates keep their first place; actions this build added go where the default puts them
    val seen = HashSet<ReaderToolbarAction>()
    val t = toolbar.filter { seen.add(it) }
    val o = overflow.filter { seen.add(it) }
    val h = hidden.filter { seen.add(it) }
    val base = defaultReaderToolbar()
    val missing = ReaderToolbarAction.entries.filter { it !in seen }
    return ReaderToolbarLayout(
        t + missing.filter { it in base.toolbar },
        o + missing.filter { it in base.overflow || (it !in base.toolbar && it !in base.hidden) },
        h + missing.filter { it in base.hidden },
    )
}

private val readerToolbarShape = Regex(
    "^T:([A-Za-z]+(?:,[A-Za-z]+)*)?\\|O:([A-Za-z]+(?:,[A-Za-z]+)*)?\\|H:([A-Za-z]+(?:,[A-Za-z]+)*)?$",
)

private fun parseReaderToolbarNames(text: String): List<ReaderToolbarAction> {
    if (text.isEmpty()) return emptyList()
    return text.split(',').mapNotNull { name ->
        ReaderToolbarAction.entries.firstOrNull { it.name == name } // F69: an unknown (newer) action is dropped
    }
}

enum class ComposeBarAction {
    Postpone,
}

data class ComposeBarLayout(
    val toolbar: List<ComposeBarAction>,
    val overflow: List<ComposeBarAction>,
    val hidden: List<ComposeBarAction>,
)

sealed interface ComposeMenuEntry {
    data class Action(val action: ComposeBarAction) : ComposeMenuEntry
    data object Divider : ComposeMenuEntry
    data object Customize : ComposeMenuEntry
}

fun defaultComposeBar(): ComposeBarLayout = ComposeBarLayout(
    toolbar = emptyList(),
    overflow = listOf(ComposeBarAction.Postpone),
    hidden = emptyList(),
)

fun resetComposeBar(): ComposeBarLayout = defaultComposeBar()

fun moveComposeAction(
    layout: ComposeBarLayout,
    action: ComposeBarAction,
    section: BarSection,
): ComposeBarLayout =
    applyMove(layout, layout.toolbar, layout.overflow, layout.hidden, moveSection(layout.toolbar, layout.overflow, layout.hidden, action, section)) { toolbar, overflow, hidden ->
        ComposeBarLayout(toolbar, overflow, hidden)
    }

fun moveComposeActionBy(
    layout: ComposeBarLayout,
    action: ComposeBarAction,
    delta: Int,
): ComposeBarLayout =
    applyMove(layout, layout.toolbar, layout.overflow, layout.hidden, moveByDelta(layout.toolbar, layout.overflow, layout.hidden, action, delta)) { toolbar, overflow, hidden ->
        ComposeBarLayout(toolbar, overflow, hidden)
    }

fun dragComposeLayout(layout: ComposeBarLayout, from: Int, to: Int): ComposeBarLayout {
    val (toolbar, overflow, hidden) = dragActions(layout.toolbar, layout.overflow, layout.hidden, from, to)
    return ComposeBarLayout(toolbar, overflow, hidden)
}

fun composeMenu(layout: ComposeBarLayout): List<ComposeMenuEntry> {
    val items = ArrayList<ComposeMenuEntry>()
    for (action in layout.overflow) items.add(ComposeMenuEntry.Action(action))
    items.add(ComposeMenuEntry.Divider)
    items.add(ComposeMenuEntry.Customize)
    return items
}

fun encodeComposeBar(layout: ComposeBarLayout): String =
    "T:${layout.toolbar.joinToString(",") { it.name }}" +
        "|O:${layout.overflow.joinToString(",") { it.name }}" +
        "|H:${layout.hidden.joinToString(",") { it.name }}"

fun parseComposeBar(value: String): ComposeBarLayout {
    val match = composeBarShape.matchEntire(value) ?: throw IllegalArgumentException("bad composeBar")
    val toolbar = parseComposeNames(match.groupValues[1])
    val overflow = parseComposeNames(match.groupValues[2])
    val hidden = parseComposeNames(match.groupValues[3])
    // F69: tolerant: duplicates keep their first place; actions this build added go where the default puts them
    val seen = HashSet<ComposeBarAction>()
    val t = toolbar.filter { seen.add(it) }
    val o = overflow.filter { seen.add(it) }
    val h = hidden.filter { seen.add(it) }
    val base = defaultComposeBar()
    val missing = ComposeBarAction.entries.filter { it !in seen }
    return ComposeBarLayout(
        t + missing.filter { it in base.toolbar },
        o + missing.filter { it in base.overflow || (it !in base.toolbar && it !in base.hidden) },
        h + missing.filter { it in base.hidden },
    )
}

private val composeBarShape = Regex(
    "^T:([A-Za-z]+(?:,[A-Za-z]+)*)?\\|O:([A-Za-z]+(?:,[A-Za-z]+)*)?\\|H:([A-Za-z]+(?:,[A-Za-z]+)*)?$",
)

private fun parseComposeNames(text: String): List<ComposeBarAction> {
    if (text.isEmpty()) return emptyList()
    return text.split(',').mapNotNull { name ->
        ComposeBarAction.entries.firstOrNull { it.name == name } // F69: an unknown (newer) action is dropped
    }
}

data class ToolbarPack(
    val rowSizes: List<Int>,
    val overflowsCap: Boolean,
)

fun packToolbar(itemCount: Int, slotsPerRow: Int, maxRows: Int): ToolbarPack {
    val slots = if (slotsPerRow < 1) 1 else slotsPerRow
    val rows = if (maxRows < 1) 1 else maxRows
    if (itemCount <= 0) return ToolbarPack(emptyList(), false)
    if (itemCount <= slots * rows) {
        val full = itemCount / slots
        val rem = itemCount % slots
        val sizes = ArrayList<Int>(if (rem == 0) full else full + 1)
        repeat(full) { sizes.add(slots) }
        if (rem != 0) sizes.add(rem)
        return ToolbarPack(sizes, false)
    }
    val sizes = ArrayList<Int>(rows)
    repeat(rows - 1) { sizes.add(slots) }
    sizes.add(itemCount - slots * (rows - 1))
    return ToolbarPack(sizes, true)
}
