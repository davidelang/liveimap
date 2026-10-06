package org.dlang.liveimap.session

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.dlang.liveimap.settings.AccountSettings
import org.dlang.liveimap.settings.SortKey
import org.dlang.liveimap.settings.StartRule

val connectedConnection: StateFlow<ConnectionState> = MutableStateFlow(ConnectionState.Connected)

interface MailSession {
    val capabilities: Set<String>
    val connectionState: StateFlow<ConnectionState>
        get() = connectedConnection
    suspend fun resume() {}
    suspend fun suspendConnections() {}
    suspend fun open(account: AccountSettings): OpenResult
    suspend fun namespaces(): List<Namespace>
    suspend fun listLevel(prefix: String, parentMailbox: String?, unreadCounts: Boolean): List<FolderEntry>
    suspend fun statusMessages(mailboxes: List<String>): Map<String, Int> = emptyMap()
    suspend fun select(mailbox: String): SelectResult
    suspend fun unselect()
    suspend fun fetchIndex(request: IndexRequest): List<IndexRow>
    suspend fun fetchStructure(uid: Long): MimePart
    suspend fun peekPart(uid: Long, section: String, offset: Int, length: Int): ByteArray
    suspend fun fetchRfc822(uid: Long): ByteArray
    suspend fun storeFlags(uids: List<Long>, add: Set<String>, remove: Set<String>)
    suspend fun storeFlagsAll(add: Set<String>, remove: Set<String>) {}
    suspend fun uidExpungeDeleted()
    suspend fun uidExpunge(uids: List<Long>) {}
    suspend fun copyThenDelete(uids: List<Long>, targetMailbox: String)
    suspend fun copyAllThenDelete(targetMailbox: String) {}
    suspend fun selectedExists(): Int = 0
    suspend fun takeCopiedUids(): List<Long> = emptyList()
    suspend fun searchText(query: String): List<Long>
    suspend fun searchCriterion(kind: String, argument: String): List<Long>
    suspend fun searchStart(rule: StartRule, byUid: Boolean, edge: SearchEdge): List<Long> =
        throw MailFailure("searchStart")

    suspend fun locateUid(uid: Long): List<Long> = throw MailFailure("locateUid")
    suspend fun sort(key: SortKey, newestFirst: Boolean): List<Long>
    suspend fun thread(key: SortKey): ThreadNode
    suspend fun watch(mailbox: String, onChange: (MailboxChange) -> Unit)
    suspend fun stopWatch()
    suspend fun append(mailbox: String, rfc822: ByteArray, flags: Set<String> = emptySet())
    suspend fun smtpSend(rfc822: ByteArray, recipients: List<String>)
    fun close()
}
