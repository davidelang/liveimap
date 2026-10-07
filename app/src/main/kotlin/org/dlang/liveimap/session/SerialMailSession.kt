package org.dlang.liveimap.session

import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.dlang.liveimap.settings.AccountSettings
import org.dlang.liveimap.settings.SortKey
import org.dlang.liveimap.settings.StartRule

class SerialMailSession(
    private val inner: MailSession,
) : MailSession {
    private val lane = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "liveimap-imap").apply { isDaemon = true }
    }.asCoroutineDispatcher()

    override val capabilities: Set<String>
        get() = inner.capabilities

    override val featureCaps: Capabilities
        get() = inner.featureCaps

    override val connectionState: StateFlow<ConnectionState>
        get() = inner.connectionState

    override suspend fun resume() {
        onLane { inner.resume() }
    }

    override suspend fun suspendConnections() {
        onLane { inner.suspendConnections() }
    }

    override suspend fun open(account: AccountSettings): OpenResult =
        onLane { inner.open(account) }

    override fun setCertConfirmer(confirm: (suspend (CertPrompt) -> Boolean)?) {
        inner.setCertConfirmer(confirm)
    }

    override suspend fun namespaces(): List<Namespace> =
        onLane { inner.namespaces() }

    override suspend fun listLevel(
        prefix: String,
        parentMailbox: String?,
        unreadCounts: Boolean,
    ): List<FolderEntry> = onLane { inner.listLevel(prefix, parentMailbox, unreadCounts) }

    override suspend fun mailboxListed(name: String): Boolean =
        onLane { inner.mailboxListed(name) }

    override suspend fun statusMessages(mailboxes: List<String>): Map<String, Int> =
        onLane { inner.statusMessages(mailboxes) }

    override suspend fun select(mailbox: String): SelectResult =
        onLane { inner.select(mailbox) }

    override suspend fun unselect() {
        onLane { inner.unselect() }
    }

    override suspend fun fetchIndex(request: IndexRequest): List<IndexRow> =
        onLane { inner.fetchIndex(request) }

    override suspend fun fetchStructure(uid: Long): MimePart =
        onLane { inner.fetchStructure(uid) }

    override suspend fun peekPart(uid: Long, section: String, offset: Int, length: Int): ByteArray =
        onLane { inner.peekPart(uid, section, offset, length) }

    override suspend fun fetchRfc822(uid: Long): ByteArray =
        onLane { inner.fetchRfc822(uid) }

    override suspend fun storeFlags(uids: List<Long>, add: Set<String>, remove: Set<String>) {
        onLane { inner.storeFlags(uids, add, remove) }
    }

    override suspend fun storeFlagsAll(add: Set<String>, remove: Set<String>) {
        onLane { inner.storeFlagsAll(add, remove) }
    }

    override suspend fun uidExpungeDeleted() {
        onLane { inner.uidExpungeDeleted() }
    }

    override suspend fun uidExpunge(uids: List<Long>) {
        onLane { inner.uidExpunge(uids) }
    }

    override suspend fun copyThenDelete(uids: List<Long>, targetMailbox: String) {
        onLane { inner.copyThenDelete(uids, targetMailbox) }
    }

    override suspend fun copyUids(uids: List<Long>, targetMailbox: String) {
        onLane { inner.copyUids(uids, targetMailbox) }
    }

    override suspend fun copyAllThenDelete(targetMailbox: String) {
        onLane { inner.copyAllThenDelete(targetMailbox) }
    }

    override suspend fun selectedExists(): Int = onLane { inner.selectedExists() }

    override suspend fun takeCopiedUids(): List<Long> = onLane { inner.takeCopiedUids() }

    override suspend fun searchText(query: String): List<Long> =
        onLane { inner.searchText(query) }

    override suspend fun searchCriterion(kind: String, argument: String): List<Long> =
        onLane { inner.searchCriterion(kind, argument) }

    override suspend fun searchCount(kind: String, argument: String): Int =
        onLane { inner.searchCount(kind, argument) }

    override suspend fun searchScope(scopeName: String, home: String, kind: String, argument: String): List<MailboxUids> =
        onLane { inner.searchScope(scopeName, home, kind, argument) }

    override suspend fun searchScopeCount(scopeName: String, home: String, kind: String, argument: String): Int =
        onLane { inner.searchScopeCount(scopeName, home, kind, argument) }

    override suspend fun subscribedMailboxes(): List<String> =
        onLane { inner.subscribedMailboxes() }

    override suspend fun searchStart(rule: StartRule, byUid: Boolean, edge: SearchEdge): List<Long> =
        onLane { inner.searchStart(rule, byUid, edge) }

    override suspend fun locateUid(uid: Long): List<Long> = onLane { inner.locateUid(uid) }

    override suspend fun sort(key: SortKey, newestFirst: Boolean): List<Long> =
        onLane { inner.sort(key, newestFirst) }

    override suspend fun thread(key: SortKey): ThreadNode =
        onLane { inner.thread(key) }

    override suspend fun clientOrder(key: SortKey, newestFirst: Boolean): List<Long> =
        onLane { inner.clientOrder(key, newestFirst) }

    override suspend fun clientThread(key: SortKey): ThreadNode =
        onLane { inner.clientThread(key) }

    override suspend fun watch(mailbox: String, onChange: (MailboxChange) -> Unit) {
        onLane { inner.watch(mailbox, onChange) }
    }

    override suspend fun stopWatch() {
        onLane { inner.stopWatch() }
    }

    override suspend fun noop() {
        onLane { inner.noop() }
    }

    override suspend fun knownTrash(): String = onLane { inner.knownTrash() }

    override suspend fun expungeOnLeave() {
        onLane { inner.expungeOnLeave() }
    }

    override suspend fun selectedInfo(): SelectResult = onLane { inner.selectedInfo() }

    override suspend fun append(mailbox: String, rfc822: ByteArray, flags: Set<String>) {
        onLane { inner.append(mailbox, rfc822, flags) }
    }

    override suspend fun appendReturningUid(mailbox: String, rfc822: ByteArray, flags: Set<String>): Long =
        onLane { inner.appendReturningUid(mailbox, rfc822, flags) }

    override suspend fun smtpSend(rfc822: ByteArray, recipients: List<String>) {
        onLane { inner.smtpSend(rfc822, recipients) }
    }

    override fun close() {
        inner.close()
    }

    private suspend fun <T> onLane(block: suspend () -> T): T = withContext(lane) { block() }
}
