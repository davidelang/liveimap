package org.dlang.liveimap.session

import org.dlang.liveimap.settings.AccountSettings
import org.dlang.liveimap.settings.SortKey

class DisconnectedMailSession : MailSession {
    override val capabilities: Set<String> = emptySet()

    override suspend fun open(account: AccountSettings): OpenResult =
        OpenResult.Failed("not connected")

    override suspend fun namespaces(): List<Namespace> = notConnected()

    override suspend fun listLevel(prefix: String, parentMailbox: String?): List<FolderEntry> =
        notConnected()

    override suspend fun select(mailbox: String): SelectResult = notConnected()

    override suspend fun unselect(): Unit = notConnected()

    override suspend fun fetchIndex(request: IndexRequest): List<IndexRow> = notConnected()

    override suspend fun fetchStructure(uid: Long): MimePart = notConnected()

    override suspend fun peekPart(uid: Long, section: String, offset: Int, length: Int): ByteArray =
        notConnected()

    override suspend fun fetchRfc822(uid: Long): ByteArray = notConnected()

    override suspend fun storeFlags(uids: List<Long>, add: Set<String>, remove: Set<String>): Unit =
        notConnected()

    override suspend fun uidExpungeDeleted(): Unit = notConnected()

    override suspend fun copyThenDelete(uids: List<Long>, targetMailbox: String): Unit =
        notConnected()

    override suspend fun searchText(query: String): List<Long> = notConnected()

    override suspend fun sort(key: SortKey, newestFirst: Boolean): List<Long> = notConnected()

    override suspend fun thread(key: SortKey): ThreadNode = notConnected()

    override suspend fun watch(mailbox: String, onChange: (MailboxChange) -> Unit): Unit =
        notConnected()

    override suspend fun stopWatch(): Unit = notConnected()

    override suspend fun append(mailbox: String, rfc822: ByteArray): Unit = notConnected()

    override suspend fun smtpSend(rfc822: ByteArray, recipients: List<String>): Unit =
        notConnected()

    override fun close() {
    }

    private fun notConnected(): Nothing = throw MailFailure("not connected")
}
