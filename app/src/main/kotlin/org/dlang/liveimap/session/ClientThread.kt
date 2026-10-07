package org.dlang.liveimap.session

import org.dlang.liveimap.settings.SortKey

private val messageIdPattern = Regex("<[^>]+>")
private val leadingReply = Regex("(?i)^(re|fwd):\\s*")

fun clientThreads(rows: List<ThreadHeader>, key: SortKey): ThreadNode {
    return when (key) {
        SortKey.ThreadOrderedSubject -> orderedSubjectThreads(rows)
        SortKey.ThreadReferences -> referenceThreads(rows)
        else -> ThreadNode(null, emptyList())
    }
}

private fun orderedSubjectThreads(rows: List<ThreadHeader>): ThreadNode {
    val groups = LinkedHashMap<String, MutableList<ThreadHeader>>()
    val blanks = ArrayList<ThreadHeader>()
    for (row in rows) {
        if (row.uid <= 0L) continue
        val subject = normalizedSubject(row.subject)
        if (subject.isEmpty()) {
            blanks.add(row)
        } else {
            groups.getOrPut(subject.lowercase()) { ArrayList() }.add(row)
        }
    }
    val roots = ArrayList<ThreadNode>()
    for (group in groups.values) {
        val sorted = group.sortedBy { it.uid }
        val children = sorted.drop(1).map { ThreadNode(it.uid, emptyList()) }
        roots.add(ThreadNode(sorted.first().uid, children))
    }
    for (row in blanks) {
        roots.add(ThreadNode(row.uid, emptyList()))
    }
    roots.sortBy { it.uid ?: Long.MAX_VALUE }
    return ThreadNode(null, roots)
}

private fun normalizedSubject(subject: String): String {
    var rest = subject.trim()
    while (true) {
        val next = leadingReply.replace(rest, "").trim()
        if (next == rest) return rest
        rest = next
    }
}

private fun referenceThreads(rows: List<ThreadHeader>): ThreadNode {
    val present = rows.filter { it.uid > 0L }.sortedBy { it.uid }
    val idToUid = HashMap<String, Long>()
    for (row in present) {
        val id = messageIds(row.messageId).firstOrNull() ?: continue
        if (id !in idToUid) idToUid[id] = row.uid
    }
    val parentOf = HashMap<Long, Long>()
    for (row in present) {
        val parent = parentUid(row, idToUid) ?: continue
        if (parent == row.uid || createsCycle(row.uid, parent, parentOf)) continue
        parentOf[row.uid] = parent
    }
    val childrenOf = HashMap<Long, MutableList<Long>>()
    val roots = ArrayList<Long>()
    for (row in present) {
        val parent = parentOf[row.uid]
        if (parent == null) {
            roots.add(row.uid)
        } else {
            childrenOf.getOrPut(parent) { ArrayList() }.add(row.uid)
        }
    }
    fun node(uid: Long): ThreadNode {
        val children = childrenOf[uid].orEmpty().sorted().map { node(it) }
        return ThreadNode(uid, children)
    }
    return ThreadNode(null, roots.sorted().map { node(it) })
}

private fun createsCycle(uid: Long, parent: Long, parentOf: Map<Long, Long>): Boolean {
    var cursor: Long? = parent
    val seen = HashSet<Long>()
    while (cursor != null) {
        if (cursor == uid || !seen.add(cursor)) return true
        cursor = parentOf[cursor]
    }
    return false
}

private fun parentUid(row: ThreadHeader, idToUid: Map<String, Long>): Long? {
    for (id in messageIds(row.references).asReversed()) {
        val uid = idToUid[id]
        if (uid != null && uid != row.uid) return uid
    }
    for (id in messageIds(row.inReplyTo).asReversed()) {
        val uid = idToUid[id]
        if (uid != null && uid != row.uid) return uid
    }
    return null
}

private fun messageIds(raw: String): List<String> {
    val found = messageIdPattern.findAll(raw).map { it.value.lowercase() }.toList()
    if (found.isNotEmpty()) return found
    val bare = raw.trim()
    if (bare.isEmpty()) return emptyList()
    val token = bare.removePrefix("<").removeSuffix(">").lowercase()
    if (token.isEmpty()) return emptyList()
    return listOf("<$token>")
}
