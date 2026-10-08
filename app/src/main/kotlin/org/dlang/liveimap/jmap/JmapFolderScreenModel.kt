package org.dlang.liveimap.jmap

import kotlinx.coroutines.CancellationException
import org.dlang.liveimap.engine.PeerTrust
import org.dlang.liveimap.settings.DeletePolicy

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
    private val markSeenOnOpen: Boolean = true,
    private val deletePolicy: DeletePolicy = DeletePolicy.MarkDeleted,
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

    fun search(mailboxId: String, text: String): JmapMessagePage {
        return jmapMessageSearch(
            session,
            mailboxId,
            text,
            0,
            60,
            username,
            password,
            pin,
            post,
            trust,
        )
    }

    fun searchSteps(mailboxId: String, operator: String, terms: List<String>): JmapMessagePage {
        return jmapMessageSearchSteps(
            session,
            mailboxId,
            operator,
            terms,
            0,
            60,
            username,
            password,
            pin,
            post,
            trust,
        )
    }

    fun body(emailId: String): String {
        return jmapMessageBody(session, emailId, username, password, pin, post, trust)
    }

    fun markSeen(emailId: String) {
        if (!markSeenOnOpen) return
        jmapMarkSeen(session, emailId, username, password, pin, post, trust)
    }

    fun deleteMessage(emailId: String, fromMailboxId: String) {
        val trashId = rows.firstOrNull { it.role == "trash" }?.id ?: ""
        jmapDelete(
            session,
            emailId,
            fromMailboxId,
            trashId,
            deletePolicy,
            username,
            password,
            pin,
            post,
            trust,
        )
    }

    fun applyPush(
        sinceState: String,
        open: (String) -> JmapHttpExchange,
    ): JmapEmailChanges? {
        return jmapApplyPush(
            session,
            sinceState,
            username,
            password,
            pin,
            open,
            post,
            trust,
        )
    }

    fun pushOpen(): (String) -> JmapHttpExchange {
        val authorization = jmapBasicAuthorization(username, password)
        return { url -> platformJmapEventSource(url, authorization) }
    }
}

class JmapListPush {
    var recorded: String? = null
        private set

    fun clear() {
        recorded = null
    }

    fun begin(emailState: String?): String? {
        if (emailState.isNullOrBlank()) return null
        if (recorded == emailState) return null
        recorded = emailState
        return emailState
    }

    fun after(
        page: JmapMessagePage,
        changes: JmapEmailChanges?,
        reloaded: JmapMessagePage? = null,
    ): JmapMessagePage {
        if (changes == null) return page
        if (changes.created.isNotEmpty() || changes.updated.isNotEmpty()) {
            val loaded = reloaded ?: return page
            val next = loaded.emailState
            if (!next.isNullOrBlank()) recorded = next
            return loaded
        }
        recorded = changes.newState
        val gone = changes.destroyed.toSet()
        return page.copy(
            messages = page.messages.filter { it.id !in gone },
            emailState = changes.newState,
        )
    }
}

fun jmapReadMessageListPush(
    page: JmapMessagePage,
    mailboxId: String?,
    push: JmapListPush,
    apply: (String) -> JmapEmailChanges?,
    reload: (String) -> JmapMessagePage,
    stillOpen: () -> Boolean,
): JmapMessagePage {
    if (!stillOpen()) return page
    val state = push.begin(page.emailState) ?: return page
    val result = try {
        apply(state)
    } catch (error: CancellationException) {
        throw error
    }
    if (!stillOpen()) return page
    if (result == null || (result.created.isEmpty() && result.updated.isEmpty())) {
        return push.after(page, result)
    }
    if (mailboxId.isNullOrBlank()) return page
    val loaded = try {
        reload(mailboxId)
    } catch (error: CancellationException) {
        throw error
    }
    if (!stillOpen()) return page
    return push.after(page, result, loaded)
}

fun jmapMessageListKeeps(page: JmapMessagePage, failure: JmapFailure): JmapMessagePage {
    return if (failure.text.isEmpty()) page else page
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
