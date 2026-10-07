package org.dlang.liveimap.ui.toolbar

import org.dlang.liveimap.settings.AccountSettings
import org.dlang.liveimap.settings.ReaderAction
import org.dlang.liveimap.settings.defaultReaderBar

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
    toolbar = listOf(IndexBarAction.Refresh, IndexBarAction.Search, IndexBarAction.Filter),
    overflow = emptyList(),
    hidden = emptyList(),
)

fun resetIndexBar(): IndexBarLayout = defaultIndexBar()

fun moveIndexAction(layout: IndexBarLayout, action: IndexBarAction, section: BarSection): IndexBarLayout {
    val current = layout.sectionOf(action) ?: return layout
    if (current == section) return layout
    val cleared = layout.copy(
        toolbar = layout.toolbar.filterNot { it == action },
        overflow = layout.overflow.filterNot { it == action },
        hidden = layout.hidden.filterNot { it == action },
    )
    return cleared.withSection(section, cleared.section(section) + action)
}

fun moveIndexActionBy(layout: IndexBarLayout, action: IndexBarAction, delta: Int): IndexBarLayout {
    if (delta != -1 && delta != 1) return layout
    val section = layout.sectionOf(action) ?: return layout
    val list = layout.section(section)
    val index = list.indexOf(action)
    if (index < 0) return layout
    val target = index + delta
    if (target !in list.indices) return layout
    val next = list.toMutableList()
    next.removeAt(index)
    next.add(target, action)
    return layout.withSection(section, next)
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
    val all = toolbar + overflow + hidden
    if (all.toSet() != IndexBarAction.entries.toSet() || all.size != all.toSet().size) {
        throw IllegalArgumentException("bad indexBar")
    }
    return IndexBarLayout(toolbar, overflow, hidden)
}

private val indexBarShape = Regex(
    "^T:([A-Za-z]+(?:,[A-Za-z]+)*)?\\|O:([A-Za-z]+(?:,[A-Za-z]+)*)?\\|H:([A-Za-z]+(?:,[A-Za-z]+)*)?$",
)

private fun parseIndexNames(text: String): List<IndexBarAction> {
    if (text.isEmpty()) return emptyList()
    return text.split(',').map { name ->
        try {
            enumValueOf<IndexBarAction>(name)
        } catch (_: IllegalArgumentException) {
            throw IllegalArgumentException("bad indexBar")
        }
    }
}

private fun IndexBarLayout.sectionOf(action: IndexBarAction): BarSection? = when {
    action in toolbar -> BarSection.Toolbar
    action in overflow -> BarSection.Overflow
    action in hidden -> BarSection.Hidden
    else -> null
}

private fun IndexBarLayout.section(section: BarSection): List<IndexBarAction> = when (section) {
    BarSection.Toolbar -> toolbar
    BarSection.Overflow -> overflow
    BarSection.Hidden -> hidden
}

private fun IndexBarLayout.withSection(section: BarSection, actions: List<IndexBarAction>): IndexBarLayout =
    when (section) {
        BarSection.Toolbar -> copy(toolbar = actions)
        BarSection.Overflow -> copy(overflow = actions)
        BarSection.Hidden -> copy(hidden = actions)
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
    toolbar = listOf(
        SelectionBarAction.Seen,
        SelectionBarAction.Flag,
        SelectionBarAction.Move,
        SelectionBarAction.Delete,
    ),
    overflow = emptyList(),
    hidden = emptyList(),
)

fun resetSelectionBar(): SelectionBarLayout = defaultSelectionBar()

fun moveSelectionAction(
    layout: SelectionBarLayout,
    action: SelectionBarAction,
    section: BarSection,
): SelectionBarLayout {
    val current = layout.sectionOf(action) ?: return layout
    if (current == section) return layout
    val cleared = layout.copy(
        toolbar = layout.toolbar.filterNot { it == action },
        overflow = layout.overflow.filterNot { it == action },
        hidden = layout.hidden.filterNot { it == action },
    )
    return cleared.withSection(section, cleared.section(section) + action)
}

fun moveSelectionActionBy(layout: SelectionBarLayout, action: SelectionBarAction, delta: Int): SelectionBarLayout {
    if (delta != -1 && delta != 1) return layout
    val section = layout.sectionOf(action) ?: return layout
    val list = layout.section(section)
    val index = list.indexOf(action)
    if (index < 0) return layout
    val target = index + delta
    if (target !in list.indices) return layout
    val next = list.toMutableList()
    next.removeAt(index)
    next.add(target, action)
    return layout.withSection(section, next)
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
    val all = toolbar + overflow + hidden
    if (all.toSet() != SelectionBarAction.entries.toSet() || all.size != all.toSet().size) {
        throw IllegalArgumentException("bad selectionBar")
    }
    return SelectionBarLayout(toolbar, overflow, hidden)
}

private val selectionBarShape = Regex(
    "^T:([A-Za-z]+(?:,[A-Za-z]+)*)?\\|O:([A-Za-z]+(?:,[A-Za-z]+)*)?\\|H:([A-Za-z]+(?:,[A-Za-z]+)*)?$",
)

private fun parseSelectionNames(text: String): List<SelectionBarAction> {
    if (text.isEmpty()) return emptyList()
    return text.split(',').map { name ->
        try {
            enumValueOf<SelectionBarAction>(name)
        } catch (_: IllegalArgumentException) {
            throw IllegalArgumentException("bad selectionBar")
        }
    }
}

private fun SelectionBarLayout.sectionOf(action: SelectionBarAction): BarSection? = when {
    action in toolbar -> BarSection.Toolbar
    action in overflow -> BarSection.Overflow
    action in hidden -> BarSection.Hidden
    else -> null
}

private fun SelectionBarLayout.section(section: BarSection): List<SelectionBarAction> = when (section) {
    BarSection.Toolbar -> toolbar
    BarSection.Overflow -> overflow
    BarSection.Hidden -> hidden
}

private fun SelectionBarLayout.withSection(
    section: BarSection,
    actions: List<SelectionBarAction>,
): SelectionBarLayout = when (section) {
    BarSection.Toolbar -> copy(toolbar = actions)
    BarSection.Overflow -> copy(overflow = actions)
    BarSection.Hidden -> copy(hidden = actions)
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
): FolderBarLayout {
    val current = layout.sectionOf(action) ?: return layout
    if (current == section) return layout
    val cleared = layout.copy(
        toolbar = layout.toolbar.filterNot { it == action },
        overflow = layout.overflow.filterNot { it == action },
        hidden = layout.hidden.filterNot { it == action },
    )
    return cleared.withSection(section, cleared.section(section) + action)
}

fun moveFolderActionBy(layout: FolderBarLayout, action: FolderBarAction, delta: Int): FolderBarLayout {
    if (delta != -1 && delta != 1) return layout
    val section = layout.sectionOf(action) ?: return layout
    val list = layout.section(section)
    val index = list.indexOf(action)
    if (index < 0) return layout
    val target = index + delta
    if (target !in list.indices) return layout
    val next = list.toMutableList()
    next.removeAt(index)
    next.add(target, action)
    return layout.withSection(section, next)
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
    val all = toolbar + overflow + hidden
    if (all.toSet() != FolderBarAction.entries.toSet() || all.size != all.toSet().size) {
        throw IllegalArgumentException("bad folderBar")
    }
    return FolderBarLayout(toolbar, overflow, hidden)
}

private val folderBarShape = Regex(
    "^T:([A-Za-z]+(?:,[A-Za-z]+)*)?\\|O:([A-Za-z]+(?:,[A-Za-z]+)*)?\\|H:([A-Za-z]+(?:,[A-Za-z]+)*)?$",
)

private fun parseFolderNames(text: String): List<FolderBarAction> {
    if (text.isEmpty()) return emptyList()
    return text.split(',').map { name ->
        try {
            enumValueOf<FolderBarAction>(name)
        } catch (_: IllegalArgumentException) {
            throw IllegalArgumentException("bad folderBar")
        }
    }
}

private fun FolderBarLayout.sectionOf(action: FolderBarAction): BarSection? = when {
    action in toolbar -> BarSection.Toolbar
    action in overflow -> BarSection.Overflow
    action in hidden -> BarSection.Hidden
    else -> null
}

private fun FolderBarLayout.section(section: BarSection): List<FolderBarAction> = when (section) {
    BarSection.Toolbar -> toolbar
    BarSection.Overflow -> overflow
    BarSection.Hidden -> hidden
}

private fun FolderBarLayout.withSection(
    section: BarSection,
    actions: List<FolderBarAction>,
): FolderBarLayout = when (section) {
    BarSection.Toolbar -> copy(toolbar = actions)
    BarSection.Overflow -> copy(overflow = actions)
    BarSection.Hidden -> copy(hidden = actions)
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
}

data class ReaderToolbarLayout(
    val toolbar: List<ReaderToolbarAction>,
    val overflow: List<ReaderToolbarAction>,
    val hidden: List<ReaderToolbarAction>,
)

fun readerToolbarFrom(saved: List<ReaderAction>): ReaderToolbarLayout {
    val shown = saved.map { it.toToolbarAction() }
    val missing = ReaderAction.entries
        .filter { it !in saved }
        .map { it.toToolbarAction() }
    return ReaderToolbarLayout(
        toolbar = listOf(ReaderToolbarAction.Refresh) + shown.take(4),
        overflow = shown.drop(4) + missing,
        hidden = emptyList(),
    )
}

fun resetReaderToolbar(): ReaderToolbarLayout = readerToolbarFrom(defaultReaderBar)

fun effectiveReaderToolbar(settings: AccountSettings): ReaderToolbarLayout =
    settings.readerToolbar ?: readerToolbarFrom(settings.readerBar)

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
): ReaderToolbarLayout {
    val current = layout.sectionOf(action) ?: return layout
    if (current == section) return layout
    val cleared = layout.copy(
        toolbar = layout.toolbar.filterNot { it == action },
        overflow = layout.overflow.filterNot { it == action },
        hidden = layout.hidden.filterNot { it == action },
    )
    return cleared.withSection(section, cleared.section(section) + action)
}

fun moveReaderActionBy(
    layout: ReaderToolbarLayout,
    action: ReaderToolbarAction,
    delta: Int,
): ReaderToolbarLayout {
    if (delta != -1 && delta != 1) return layout
    val section = layout.sectionOf(action) ?: return layout
    val list = layout.section(section)
    val index = list.indexOf(action)
    if (index < 0) return layout
    val target = index + delta
    if (target !in list.indices) return layout
    val next = list.toMutableList()
    next.removeAt(index)
    next.add(target, action)
    return layout.withSection(section, next)
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
    val all = toolbar + overflow + hidden
    if (all.toSet() != ReaderToolbarAction.entries.toSet() || all.size != all.toSet().size) {
        throw IllegalArgumentException("bad readerToolbar")
    }
    return ReaderToolbarLayout(toolbar, overflow, hidden)
}

private val readerToolbarShape = Regex(
    "^T:([A-Za-z]+(?:,[A-Za-z]+)*)?\\|O:([A-Za-z]+(?:,[A-Za-z]+)*)?\\|H:([A-Za-z]+(?:,[A-Za-z]+)*)?$",
)

private fun parseReaderToolbarNames(text: String): List<ReaderToolbarAction> {
    if (text.isEmpty()) return emptyList()
    return text.split(',').map { name ->
        try {
            enumValueOf<ReaderToolbarAction>(name)
        } catch (_: IllegalArgumentException) {
            throw IllegalArgumentException("bad readerToolbar")
        }
    }
}

private fun ReaderAction.toToolbarAction(): ReaderToolbarAction = when (this) {
    ReaderAction.Reply -> ReaderToolbarAction.Reply
    ReaderAction.ReplyAll -> ReaderToolbarAction.ReplyAll
    ReaderAction.Forward -> ReaderToolbarAction.Forward
    ReaderAction.Delete -> ReaderToolbarAction.Delete
    ReaderAction.Move -> ReaderToolbarAction.Move
    ReaderAction.Spam -> ReaderToolbarAction.Spam
    ReaderAction.Bounce -> ReaderToolbarAction.Bounce
}

private fun ReaderToolbarLayout.sectionOf(action: ReaderToolbarAction): BarSection? = when {
    action in toolbar -> BarSection.Toolbar
    action in overflow -> BarSection.Overflow
    action in hidden -> BarSection.Hidden
    else -> null
}

private fun ReaderToolbarLayout.section(section: BarSection): List<ReaderToolbarAction> = when (section) {
    BarSection.Toolbar -> toolbar
    BarSection.Overflow -> overflow
    BarSection.Hidden -> hidden
}

private fun ReaderToolbarLayout.withSection(
    section: BarSection,
    actions: List<ReaderToolbarAction>,
): ReaderToolbarLayout = when (section) {
    BarSection.Toolbar -> copy(toolbar = actions)
    BarSection.Overflow -> copy(overflow = actions)
    BarSection.Hidden -> copy(hidden = actions)
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
): ComposeBarLayout {
    val current = layout.sectionOf(action) ?: return layout
    if (current == section) return layout
    val cleared = layout.copy(
        toolbar = layout.toolbar.filterNot { it == action },
        overflow = layout.overflow.filterNot { it == action },
        hidden = layout.hidden.filterNot { it == action },
    )
    return cleared.withSection(section, cleared.section(section) + action)
}

fun moveComposeActionBy(
    layout: ComposeBarLayout,
    action: ComposeBarAction,
    delta: Int,
): ComposeBarLayout {
    if (delta != -1 && delta != 1) return layout
    val section = layout.sectionOf(action) ?: return layout
    val list = layout.section(section)
    val index = list.indexOf(action)
    if (index < 0) return layout
    val target = index + delta
    if (target !in list.indices) return layout
    val next = list.toMutableList()
    next.removeAt(index)
    next.add(target, action)
    return layout.withSection(section, next)
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
    val all = toolbar + overflow + hidden
    if (all.toSet() != ComposeBarAction.entries.toSet() || all.size != all.toSet().size) {
        throw IllegalArgumentException("bad composeBar")
    }
    return ComposeBarLayout(toolbar, overflow, hidden)
}

private val composeBarShape = Regex(
    "^T:([A-Za-z]+(?:,[A-Za-z]+)*)?\\|O:([A-Za-z]+(?:,[A-Za-z]+)*)?\\|H:([A-Za-z]+(?:,[A-Za-z]+)*)?$",
)

private fun parseComposeNames(text: String): List<ComposeBarAction> {
    if (text.isEmpty()) return emptyList()
    return text.split(',').map { name ->
        try {
            enumValueOf<ComposeBarAction>(name)
        } catch (_: IllegalArgumentException) {
            throw IllegalArgumentException("bad composeBar")
        }
    }
}

private fun ComposeBarLayout.sectionOf(action: ComposeBarAction): BarSection? = when {
    action in toolbar -> BarSection.Toolbar
    action in overflow -> BarSection.Overflow
    action in hidden -> BarSection.Hidden
    else -> null
}

private fun ComposeBarLayout.section(section: BarSection): List<ComposeBarAction> = when (section) {
    BarSection.Toolbar -> toolbar
    BarSection.Overflow -> overflow
    BarSection.Hidden -> hidden
}

private fun ComposeBarLayout.withSection(
    section: BarSection,
    actions: List<ComposeBarAction>,
): ComposeBarLayout = when (section) {
    BarSection.Toolbar -> copy(toolbar = actions)
    BarSection.Overflow -> copy(overflow = actions)
    BarSection.Hidden -> copy(hidden = actions)
}
