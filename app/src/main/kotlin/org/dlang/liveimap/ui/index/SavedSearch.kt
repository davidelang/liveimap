package org.dlang.liveimap.ui.index

data class SavedSearch(
    val name: String,
    val query: String,
    val field: SimpleSearchField,
)

fun saveSearch(
    saved: List<SavedSearch>,
    name: String,
    query: String,
    field: SimpleSearchField,
): List<SavedSearch> {
    val trimmed = name.trim()
    if (trimmed.isEmpty() || query.isEmpty()) {
        return saved.toList()
    }
    val next = ArrayList<SavedSearch>(saved.size + 1)
    var replaced = false
    for (item in saved) {
        if (!replaced && item.name == trimmed) {
            next.add(SavedSearch(trimmed, query, field))
            replaced = true
        } else {
            next.add(item)
        }
    }
    if (!replaced) {
        next.add(SavedSearch(trimmed, query, field))
    }
    return next
}

fun recallSearch(saved: List<SavedSearch>, name: String): SavedSearch? {
    val trimmed = name.trim()
    if (trimmed.isEmpty()) return null
    for (item in saved) {
        if (item.name == trimmed) return item
    }
    return null
}
