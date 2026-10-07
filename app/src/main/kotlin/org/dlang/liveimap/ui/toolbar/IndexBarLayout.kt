package org.dlang.liveimap.ui.toolbar

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
