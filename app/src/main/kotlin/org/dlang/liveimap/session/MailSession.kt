package org.dlang.liveimap.session

import org.dlang.liveimap.settings.AccountSettings
import org.dlang.liveimap.settings.SortKey

interface MailSession {
    val capabilities: Set<String>
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
    suspend fun sort(key: SortKey, newestFirst: Boolean): List<Long>
    suspend fun thread(key: SortKey): ThreadNode
    suspend fun watch(mailbox: String, onChange: (MailboxChange) -> Unit)
    suspend fun stopWatch()
    suspend fun append(mailbox: String, rfc822: ByteArray, flags: Set<String> = emptySet())
    suspend fun smtpSend(rfc822: ByteArray, recipients: List<String>)
    fun close()
}
