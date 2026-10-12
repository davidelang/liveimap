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
    /**
     * F116/F105: run [block] with the selection pinned. No other caller can select (or issue any command) until it
     * returns, so "select folder X, check UIDVALIDITY, then act by UID" can't be split by another screen. Reentrant.
     */
    @Throws(MailFailure::class)
    suspend fun <T> pinned(block: suspend () -> T): T = block()

    val capabilities: Set<String>
    val featureCaps: Capabilities
        get() = Capabilities.parse(capabilities.joinToString(" "))
    val connectionState: StateFlow<ConnectionState>
        get() = connectedConnection
    suspend fun resume() {}
    suspend fun suspendConnections() {}
    suspend fun open(account: AccountSettings): OpenResult

    /**
     * Open with this account's own secrets rather than the selected account's: a sending rule routes the message
     * through another account. Certificate and plaintext confirmations are refused here (they would be saved to
     * the wrong account); open that account normally once first.
     */
    suspend fun openWith(credentials: org.dlang.liveimap.accounts.AccountCredentials): OpenResult = open(credentials.settings)
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
    // F71: mail-changing defaults fail rather than succeed silently (the index would show the change and offer undo).
    suspend fun storeFlagsAll(add: Set<String>, remove: Set<String>) {
        throw MailFailure("not supported")
    }
    suspend fun uidExpungeDeleted()
    suspend fun uidExpunge(uids: List<Long>) {
        throw MailFailure("not supported")
    }
    suspend fun copyThenDelete(uids: List<Long>, targetMailbox: String)
    suspend fun copyUids(uids: List<Long>, targetMailbox: String) {
        throw MailFailure("copy failed")
    }
    suspend fun copyAllThenDelete(targetMailbox: String) {
        throw MailFailure("not supported")
    }
    suspend fun selectedExists(): Int = 0
    suspend fun takeCopiedUids(): List<Long> = emptyList()
    /** UIDVALIDITY of the folder the last COPY/MOVE copied into (from COPYUID); 0 when unknown. */
    suspend fun copiedEpoch(): Long = 0L
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

    /**
     * SORT / ESORT PARTIAL / THREAD over only the messages matching [criteria] (a filter chain as
     * IndexModel.groupArgument), sent in the same command. Null when the session cannot (the caller then restricts
     * the full order to the filter's single search result).
     */
    suspend fun sortMatching(key: SortKey, newestFirst: Boolean, criteria: String): List<Long>? = null

    suspend fun sortWindowMatching(key: SortKey, newestFirst: Boolean, first: Int, last: Int, criteria: String): UidWindow? =
        null

    suspend fun threadMatching(key: SortKey, criteria: String): ThreadNode? = null
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
    /** F115: CLOSE [mailbox] (the folder the user is leaving), only with auto-expunge on. */
    suspend fun expungeOnLeave(mailbox: String) {}
    suspend fun selectedInfo(): SelectResult = SelectResult(0, 0, 0)
    suspend fun append(mailbox: String, rfc822: ByteArray, flags: Set<String> = emptySet())
    suspend fun appendReturningUid(mailbox: String, rfc822: ByteArray, flags: Set<String> = emptySet()): Long {
        append(mailbox, rfc822, flags)
        return 0
    }
    /** [dsnNotify]: RFC 3461 NOTIFY mask, 0 = none. Returns 1 DSN requested, -1 asked but not offered, 0 none. */
    suspend fun smtpSend(rfc822: ByteArray, recipients: List<String>, dsnNotify: Int = 0): Int
    /** As [smtpSend] with MAIL FROM [envelopeFrom]; empty = the account address. Sessions without SMTP ignore it. */
    suspend fun smtpSendFrom(rfc822: ByteArray, recipients: List<String>, dsnNotify: Int, envelopeFrom: String): Int =
        smtpSend(rfc822, recipients, dsnNotify)
    fun close()
}
