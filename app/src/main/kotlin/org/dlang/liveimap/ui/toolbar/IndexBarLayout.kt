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
