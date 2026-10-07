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

fun mailboxMarkedTrash(entries: List<FolderEntry>): String {
    for (entry in entries) {
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

data class ThreadNode(
    val uid: Long?,
    val children: List<ThreadNode>,
)

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
)

data class SelectedAddress(
    val name: String,
    val email: String,
)
