package org.dlang.liveimap.session

class MailFailure(val text: String) : Exception(text)

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
)

enum class IndexMode {
    ArrivalNewest,
    ArrivalOldest,
    ByUid,
}

data class IndexRequest(
    val mailbox: String,
    val mode: IndexMode,
    val uids: List<Long> = emptyList(),
    val limit: Int = 60,
    val prefetch: Int = 60,
    val includePreview: Boolean = false,
    val previewByteLimit: Int = 2048,
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
)

data class MimePart(
    val section: String,
    val type: String,
    val subtype: String,
    val disposition: String,
    val filename: String?,
    val size: Int,
    val children: List<MimePart>,
)

sealed class MailboxChange {
    data class Exists(val exists: Int) : MailboxChange()
    data class Expunge(val exists: Int) : MailboxChange()
    data class Flags(val uid: Long, val flags: Set<String>) : MailboxChange()
    data object UidValidityReset : MailboxChange()
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
