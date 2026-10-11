package org.dlang.liveimap.session

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.dlang.liveimap.settings.AccountSettings
import org.dlang.liveimap.settings.SortKey
import org.dlang.liveimap.settings.StartRule

val connectedConnection: StateFlow<ConnectionState> = MutableStateFlow(ConnectionState.Connected)

data class CertPrompt(
    val reason: String,
    val subject: String,
    val issuer: String,
    val notBefore: String,
    val notAfter: String,
    val fingerprint: String,
    val host: String,
    val port: Int,
)

interface MailSession {
    val capabilities: Set<String>
    val featureCaps: Capabilities
        get() = Capabilities.parse(capabilities.joinToString(" "))
    val connectionState: StateFlow<ConnectionState>
        get() = connectedConnection
    suspend fun resume() {}
    suspend fun suspendConnections() {}
    suspend fun open(account: AccountSettings): OpenResult
    fun setCertConfirmer(confirm: (suspend (CertPrompt) -> Boolean)?) {}
    fun setPlaintextConfirmer(confirm: (suspend () -> Boolean)?) {}
    suspend fun namespaces(): List<Namespace>
    suspend fun listLevel(prefix: String, parentMailbox: String?, unreadCounts: Boolean): List<FolderEntry>
    suspend fun mailboxListed(name: String): Boolean = throw MailFailure("list failed")
    suspend fun statusMessages(mailboxes: List<String>): Map<String, Int> = emptyMap()
    suspend fun select(mailbox: String): SelectResult
    suspend fun unselect()
    suspend fun fetchIndex(request: IndexRequest): List<IndexRow>
    suspend fun fetchStructure(uid: Long): MimePart
    /** The structure below one part (for a "nested part" entry past [MaxMimeDepth]); sections stay absolute. */
    suspend fun fetchStructureAt(uid: Long, section: String): MimePart = throw MailFailure("nested parts not supported")
    suspend fun peekPart(uid: Long, section: String, offset: Int, length: Int): ByteArray
    suspend fun fetchRfc822(uid: Long): ByteArray
    suspend fun storeFlags(uids: List<Long>, add: Set<String>, remove: Set<String>)
    suspend fun storeFlagsAll(add: Set<String>, remove: Set<String>) {}
    suspend fun uidExpungeDeleted()
    suspend fun uidExpunge(uids: List<Long>) {}
    suspend fun copyThenDelete(uids: List<Long>, targetMailbox: String)
    suspend fun copyUids(uids: List<Long>, targetMailbox: String) {
        throw MailFailure("copy failed")
    }
    suspend fun copyAllThenDelete(targetMailbox: String) {}
    suspend fun selectedExists(): Int = 0
    suspend fun takeCopiedUids(): List<Long> = emptyList()
    suspend fun searchText(query: String): List<Long>
    suspend fun searchCriterion(kind: String, argument: String): List<Long>
    suspend fun searchCount(kind: String, argument: String): Int =
        searchCriterion(kind, argument).size

    suspend fun searchScope(scopeName: String, home: String, kind: String, argument: String): List<MailboxUids> =
        throw MailFailure("search failed")

    suspend fun searchScopeCount(scopeName: String, home: String, kind: String, argument: String): Int =
        throw MailFailure("search failed")

    suspend fun subscribedMailboxes(): List<String> = emptyList()
    suspend fun searchStart(rule: StartRule, byUid: Boolean, edge: SearchEdge): List<Long> =
        throw MailFailure("searchStart")

    suspend fun locateUid(uid: Long): List<Long> = throw MailFailure("locateUid")
    suspend fun sort(key: SortKey, newestFirst: Boolean): List<Long>

    /**
     * Positions [first]..[last] (1-based) of the sorted order and the total, via ESORT PARTIAL. Null when the
     * server cannot answer a window (no ESORT/PARTIAL); callers then fall back to [sort].
     */
    suspend fun sortWindow(key: SortKey, newestFirst: Boolean, first: Int, last: Int): UidWindow? = null

    /**
     * A window of one criterion's matches in UID order (negative positions count from the newest, -1 = newest)
     * plus the total, via ESEARCH PARTIAL. Null when unsupported; fall back to [searchCriterion].
     */
    suspend fun searchWindow(kind: String, argument: String, first: Int, last: Int): UidWindow? = null
    suspend fun thread(key: SortKey): ThreadNode
    suspend fun clientOrder(
        key: SortKey,
        newestFirst: Boolean,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
        cancelled: () -> Boolean = { false },
    ): List<Long> =
        throw MailFailure("SORT was not advertised")

    suspend fun clientThread(key: SortKey): ThreadNode =
        throw MailFailure("THREAD=REFERENCES was not advertised")

    suspend fun watch(mailbox: String, onChange: (MailboxChange) -> Unit)
    suspend fun stopWatch()
    suspend fun noop() {}
    suspend fun knownTrash(): String = ""
    suspend fun expungeOnLeave() {}
    suspend fun selectedInfo(): SelectResult = SelectResult(0, 0, 0)
    suspend fun append(mailbox: String, rfc822: ByteArray, flags: Set<String> = emptySet())
    suspend fun appendReturningUid(mailbox: String, rfc822: ByteArray, flags: Set<String> = emptySet()): Long {
        append(mailbox, rfc822, flags)
        return 0
    }
    suspend fun smtpSend(rfc822: ByteArray, recipients: List<String>)
    fun close()
}
