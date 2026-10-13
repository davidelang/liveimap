package org.dlang.liveimap.session

open class MailFailure(val text: String) : Exception(text)

class ConnectionLost(text: String) : MailFailure(text)

sealed interface ConnectionState {
    data object Connected : ConnectionState
    data object Reconnecting : ConnectionState
    data object Suspended : ConnectionState
    data class Lost(val text: String) : ConnectionState
}

sealed class OpenResult {
    data object Connected : OpenResult()
    data class Rejected(val capabilities: String) : OpenResult()
    data class Failed(val text: String) : OpenResult()
}

enum class NamespaceKind {
    Personal,
    Other,
    Shared,
}

data class Namespace(
    val prefix: String,
    val delimiter: Char,
    val kind: NamespaceKind,
)

data class FolderEntry(
    val mailbox: String,
    val leaf: String,
    val hasChildren: Boolean,
    val delimiter: Char,
    val specialUse: String? = null,
    val messages: Int? = null,
    val unseen: Int? = null,
)

/** F90: INBOX is never Trash, whatever SPECIAL-USE or a setting says (deletes there would expunge with no undo). */
fun usableTrashName(name: String): String = if (name.equals("INBOX", ignoreCase = true)) "" else name

fun mailboxMarkedTrash(entries: List<FolderEntry>): String {
    for (entry in entries) {
        if (usableTrashName(entry.mailbox).isEmpty()) continue
        val tokens = entry.specialUse.orEmpty().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (tokens.any { it.equals("\\Trash", ignoreCase = true) }) return entry.mailbox
    }
    return ""
}

enum class SearchEdge {
    All,
    Min,
    Max,
}

data class MailboxUids(
    val mailbox: String,
    val uids: List<Long>,
)

enum class IndexMode {
    ArrivalNewest,
    ArrivalOldest,
    ByUid,
    ArrivalRange,
}

/** One window of a sorted or searched UID list, and the size of the whole list. */
data class UidWindow(val total: Int, val uids: List<Long>)

data class IndexRequest(
    val mailbox: String,
    val mode: IndexMode,
    val uids: List<Long> = emptyList(),
    val limit: Int = 60,
    val prefetch: Int = 60,
    val includePreview: Boolean = false,
    val previewByteLimit: Int = 2048,
    val firstSequence: Int = 0,
    val lastSequence: Int = 0,
)

data class IndexRow(
    val uid: Long,
    val sequence: Int,
    val flags: Set<String>,
    val internalDateEpoch: Long,
    val size: Int,
    val from: String,
    val subject: String,
    val envelopeDate: String,
    val preview: String?,
    val toMe: Boolean = false,
    val hasAttachment: Boolean = false,
    val recipients: String = "",
    val mailbox: String = "",
)

/** MIME parts nested deeper than this are listed as one openable "nested part" entry instead of being walked. */
const val MaxMimeDepth = 64

/** libetpan's placeholder for a BODYSTRUCTURE subtree past its own 128-level limit (patch imap-nesting-depth). */
const val LibetpanTooDeepSubtype = "x-libetpan-too-deep"

/** Stand-in the native layer returns for parts nested deeper than [MaxMimeDepth]. */
const val TooDeepSubtype = "x-liveimap-too-deep"

data class MimePart(
    val section: String,
    val type: String,
    val subtype: String,
    val disposition: String,
    val filename: String?,
    val size: Int,
    val children: List<MimePart>,
    val charset: String = "",
    val encoding: String = "",
)

sealed class MailboxChange {
    data class Exists(val exists: Int) : MailboxChange()
    data class Expunge(val exists: Int) : MailboxChange()
    data class Flags(val uid: Long, val flags: Set<String>) : MailboxChange()
    data object UidValidityReset : MailboxChange()
    data object WatchLost : MailboxChange()
    data object Reconnected : MailboxChange()
}

data class SelectResult(
    val uidValidity: Long,
    val uidNext: Long,
    val exists: Int,
)

/**
 * One node of a thread tree. Threads can be thousands of levels deep (one level per reply), so equality, hashing
 * and toString are iterative instead of the recursive data-class defaults.
 */
data class ThreadNode(
    val uid: Long?,
    val children: List<ThreadNode>,
) {
    override fun equals(other: Any?): Boolean {
        if (other !is ThreadNode) return false
        val stack = ArrayDeque<Pair<ThreadNode, ThreadNode>>()
        stack.addLast(this to other)
        while (stack.isNotEmpty()) {
            val (a, b) = stack.removeLast()
            if (a === b) continue
            if (a.uid != b.uid || a.children.size != b.children.size) return false
            for (i in a.children.indices) stack.addLast(a.children[i] to b.children[i])
        }
        return true
    }

    override fun hashCode(): Int {
        var h = 1
        val stack = ArrayDeque<ThreadNode>()
        stack.addLast(this)
        while (stack.isNotEmpty()) {
            val n = stack.removeLast()
            h = 31 * h + (n.uid?.hashCode() ?: 0)
            h = 31 * h + n.children.size
            for (i in n.children.indices.reversed()) stack.addLast(n.children[i])
        }
        return h
    }

    override fun toString(): String = "ThreadNode(uid=$uid, children=${children.size}, messages=${messageCount()})"

    /** Number of real messages (non-null uid) in this subtree, iteratively. */
    fun messageCount(): Int {
        var n = 0
        forEachPreorder { if (it.uid != null) n++ }
        return n
    }

    /** Preorder visit (node before its children, children in order) with an explicit stack. */
    inline fun forEachPreorder(visit: (ThreadNode) -> Unit) {
        val stack = ArrayDeque<ThreadNode>()
        stack.addLast(this)
        while (stack.isNotEmpty()) {
            val n = stack.removeLast()
            visit(n)
            for (i in n.children.indices.reversed()) stack.addLast(n.children[i])
        }
    }
}

data class SortField(
    val uid: Long,
    val text: String,
    val number: Long,
    val empty: Boolean,
)

data class ThreadHeader(
    val uid: Long,
    val messageId: String,
    val references: String,
    val inReplyTo: String,
    val subject: String,
)

enum class ComposeKind {
    New,
    Reply,
    ReplyAll,
    Forward,
    Bounce,
    ResumePostpone,
}

data class ComposeSeed(
    val kind: ComposeKind,
    val mailbox: String?,
    val uids: List<Long> = emptyList(),
    /** F105/F116: UIDVALIDITY of [mailbox] when [uids] were listed; 0 = captured on first use. */
    val uidValidity: Long = 0L,
    /** F121: the account the source messages (and the reply) belong to; "" = the active account at open time. */
    val accountId: String = "",
) {
    /** The source messages as references (empty until the epoch is known). */
    fun refs(accountId: String): List<MessageRef> =
        if (mailbox == null || uidValidity == 0L) emptyList() else uids.map { MessageRef(accountId, mailbox, uidValidity, it) }
}

data class SelectedAddress(
    val name: String,
    val email: String,
)
