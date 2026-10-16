package org.dlang.liveimap.session

import org.dlang.liveimap.settings.SortKey
import org.dlang.liveimap.ui.compose.decodeHeaderWords

private val messageIdPattern = Regex("<[^>]+>")

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
        val subject = baseSubject(decodeHeaderWords(row.subject))
        if (subject.isEmpty()) {
            blanks.add(row)
        } else {
            groups.getOrPut(subject) { ArrayList() }.add(row)
        }
    }
    val threads = ArrayList<Pair<ThreadHeader, List<ThreadHeader>>>()
    for (group in groups.values) {
        val sorted = group.sortedWith { a, b -> compareDateThenUid(a, b) }
        threads.add(sorted.first() to sorted.drop(1))
    }
    for (row in blanks) threads.add(row to emptyList())
    threads.sortWith { a, b -> compareDateThenUid(a.first, b.first) }
    val roots = threads.map { (root, kids) ->
        ThreadNode(root.uid, kids.map { ThreadNode(it.uid, emptyList()) })
    }
    return ThreadNode(null, roots)
}

/** A blank or unparsable date sorts after every parsed date. Equal dates order by uid ascending. */
private fun compareDateThenUid(a: ThreadHeader, b: ThreadHeader): Int {
    val ae = dateHeaderEpoch(a.dateHeader)
    val be = dateHeaderEpoch(b.dateHeader)
    if (ae == null && be != null) return 1
    if (ae != null && be == null) return -1
    if (ae != null && be != null) {
        val byDate = ae.compareTo(be)
        if (byDate != 0) return byDate
    }
    return a.uid.compareTo(b.uid)
}

private fun referenceThreads(rows: List<ThreadHeader>): ThreadNode {
    val present = rows.filter { it.uid > 0L }.sortedBy { it.uid }
    val idToUid = HashMap<String, Long>()
    for (row in present) {
        val id = messageIds(row.messageId).firstOrNull() ?: continue
        if (id !in idToUid) idToUid[id] = row.uid
    }
    val parentOf = HashMap<Long, Long>()
    // While every link points to a smaller uid, following parents only decreases, so no cycle is possible and the
    // O(depth) walk is skipped (a long reply chain would otherwise cost O(n^2)).
    var allDescending = true
    for (row in present) {
        val parent = parentUid(row, idToUid) ?: continue
        if (parent == row.uid) continue
        if (!(allDescending && parent < row.uid) && createsCycle(row.uid, parent, parentOf)) continue
        if (parent > row.uid) allDescending = false
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
    // A References chain nests one level per reply, so the tree is built bottom-up with an explicit stack (no
    // recursion, no depth limit): a node is made once all of its children are made.
    val built = HashMap<Long, ThreadNode>()
    val stack = ArrayDeque<Pair<Long, Boolean>>()
    for (root in roots.sortedDescending()) stack.addLast(root to false)
    while (stack.isNotEmpty()) {
        val (uid, expanded) = stack.removeLast()
        val kids = childrenOf[uid].orEmpty().sorted()
        if (!expanded) {
            stack.addLast(uid to true)
            for (k in kids.asReversed()) stack.addLast(k to false)
        } else {
            built[uid] = ThreadNode(uid, kids.map { built.remove(it)!! })
        }
    }
    return ThreadNode(null, roots.sorted().map { built.remove(it)!! })
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
