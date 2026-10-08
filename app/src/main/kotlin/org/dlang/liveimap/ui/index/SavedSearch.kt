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

fun deleteSavedSearch(saved: List<SavedSearch>, name: String): List<SavedSearch> {
    val trimmed = name.trim()
    if (trimmed.isEmpty()) return saved.toList()
    val next = ArrayList<SavedSearch>(saved.size)
    for (item in saved) {
        if (item.name != trimmed) next.add(item)
    }
    return next
}

data class SavedAdvanced(
    val name: String,
    val text: String,
    val scope: SearchScope,
)

fun saveAdvanced(
    saved: List<SavedAdvanced>,
    name: String,
    text: String,
    scope: SearchScope,
): List<SavedAdvanced> {
    val trimmed = name.trim()
    if (trimmed.isEmpty() || text.isEmpty()) {
        return saved.toList()
    }
    val next = ArrayList<SavedAdvanced>(saved.size + 1)
    var replaced = false
    for (item in saved) {
        if (!replaced && item.name == trimmed) {
            next.add(SavedAdvanced(trimmed, text, scope))
            replaced = true
        } else {
            next.add(item)
        }
    }
    if (!replaced) {
        next.add(SavedAdvanced(trimmed, text, scope))
    }
    return next
}

fun recallAdvanced(saved: List<SavedAdvanced>, name: String): SavedAdvanced? {
    val trimmed = name.trim()
    if (trimmed.isEmpty()) return null
    for (item in saved) {
        if (item.name == trimmed) return item
    }
    return null
}

fun deleteAdvanced(saved: List<SavedAdvanced>, name: String): List<SavedAdvanced> {
    val trimmed = name.trim()
    if (trimmed.isEmpty()) return saved.toList()
    val next = ArrayList<SavedAdvanced>(saved.size)
    for (item in saved) {
        if (item.name != trimmed) next.add(item)
    }
    return next
}
