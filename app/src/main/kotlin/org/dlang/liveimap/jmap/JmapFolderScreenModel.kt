package org.dlang.liveimap.jmap

import org.dlang.liveimap.engine.PeerTrust

fun jmapFolderLine(row: JmapFolderRow): String {
    if (row.unread > 0L) return row.name + " " + row.unread.toString()
    return row.name
}

fun jmapFolderDepth(rows: List<JmapFolderRow>, index: Int): Int {
    if (index < 0 || index >= rows.size) {
        throw JmapFailure("jmap folder index is outside the list")
    }
    val seen = HashSet<String>()
    seen.add(rows[index].id)
    var parentId = rows[index].parentId
    var depth = 0
    while (parentId != null) {
        if (parentId in seen) return depth
        val parentIndex = indexOfFolder(rows, parentId)
        if (parentIndex < 0) return depth
        seen.add(parentId)
        depth += 1
        parentId = rows[parentIndex].parentId
    }
    return depth
}

fun jmapFolderLabel(rows: List<JmapFolderRow>, index: Int): String {
    return "  ".repeat(jmapFolderDepth(rows, index)) + jmapFolderLine(rows[index])
}

fun jmapRowsWithoutDescendants(
    rows: List<JmapFolderRow>,
    ancestorId: String,
): List<JmapFolderRow> {
    return rows.filter { row ->
        row.id == ancestorId || !parentChainIncludes(rows, row, ancestorId)
    }
}

class JmapFolderScreenModel(
    private val session: JmapSession,
    private val username: String,
    private val password: String,
    private val pin: String,
    private val post: (String, String, String) -> JmapHttpExchange,
    private val trust: (String, List<ByteArray>, String) -> String = PeerTrust::check,
) {
    private val expanded = HashSet<String>()

    var rows: List<JmapFolderRow> = emptyList()
        private set

    fun loadTop() {
        val loaded = jmapFolderLevel(session, null, username, password, pin, post, trust)
        expanded.clear()
        rows = loaded
    }

    fun toggle(id: String) {
        val index = indexOfFolder(rows, id)
        if (index < 0) return
        if (!rows[index].hasChildren) return
        if (id in expanded) {
            expanded.remove(id)
            rows = jmapRowsWithoutDescendants(rows, id)
            expanded.retainAll(rows.map { it.id })
            return
        }
        val children = jmapFolderLevel(session, id, username, password, pin, post, trust)
        val next = rows.toMutableList()
        next.addAll(index + 1, children)
        rows = next
        expanded.add(id)
    }

    fun messages(mailboxId: String): JmapMessagePage {
        return jmapMessageWindow(session, mailboxId, 0, 60, username, password, pin, post, trust)
    }
}

private fun indexOfFolder(rows: List<JmapFolderRow>, id: String): Int {
    return rows.indexOfFirst { it.id == id }
}

private fun parentChainIncludes(
    rows: List<JmapFolderRow>,
    row: JmapFolderRow,
    ancestorId: String,
): Boolean {
    val seen = HashSet<String>()
    seen.add(row.id)
    var parentId = row.parentId
    while (parentId != null) {
        if (parentId == ancestorId) return true
        if (parentId in seen) return false
        val parentIndex = indexOfFolder(rows, parentId)
        if (parentIndex < 0) return false
        seen.add(parentId)
        parentId = rows[parentIndex].parentId
    }
    return false
}
