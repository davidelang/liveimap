package org.dlang.liveimap.session

import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.StateFlow
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.dlang.liveimap.settings.AccountSettings
import org.dlang.liveimap.settings.SortKey
import org.dlang.liveimap.settings.StartRule

internal class LifecycleClock {
    val current = AtomicLong(0)
    val latestResume = AtomicLong(0)
    val latestSuspend = AtomicLong(0)

    fun takeResume(): Long {
        val epoch = current.incrementAndGet()
        latestResume.set(epoch)
        return epoch
    }

    fun takeSuspend(): Long {
        val epoch = current.incrementAndGet()
        latestSuspend.set(epoch)
        return epoch
    }
}

internal class LifecycleCall(
    val clock: LifecycleClock,
    val epoch: Long,
) : AbstractCoroutineContextElement(LifecycleCall) {
    companion object Key : CoroutineContext.Key<LifecycleCall>
}

class SerialMailSession(
    private val inner: MailSession,
) : MailSession {
    private val lifecycleClock = LifecycleClock()
    // F84: one lane thread per session, created on demand and shut down by close(); a session that is
    // opened again after close() gets a fresh lane.
    private val laneLock = Any()
    private var laneDispatcher: kotlinx.coroutines.ExecutorCoroutineDispatcher? = null

    private val lane: kotlinx.coroutines.ExecutorCoroutineDispatcher
        get() = synchronized(laneLock) {
            laneDispatcher ?: Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "liveimap-imap").apply { isDaemon = true }
            }.asCoroutineDispatcher().also { laneDispatcher = it }
        }

    internal fun laneOpen(): Boolean = synchronized(laneLock) { laneDispatcher != null }

    override val capabilities: Set<String>
        get() = inner.capabilities

    override val featureCaps: Capabilities
        get() = inner.featureCaps

    override val connectionState: StateFlow<ConnectionState>
        get() = inner.connectionState

    override suspend fun resume() {
        val epoch = lifecycleClock.takeResume()
        withContext(LifecycleCall(lifecycleClock, epoch)) {
            withContext(Dispatchers.IO) { inner.resume() }
        }
    }

    override suspend fun suspendConnections() {
        val epoch = lifecycleClock.takeSuspend()
        withContext(LifecycleCall(lifecycleClock, epoch)) {
            onLane { inner.suspendConnections() }
        }
    }

    override suspend fun open(account: AccountSettings): OpenResult =
        onLane { inner.open(account) }

    override suspend fun openWith(credentials: org.dlang.liveimap.accounts.AccountCredentials): OpenResult =
        onLane { inner.openWith(credentials) }

    override fun setCertConfirmer(confirm: (suspend (CertPrompt) -> Boolean)?) {
        inner.setCertConfirmer(confirm)
    }

    override fun setPlaintextConfirmer(confirm: (suspend () -> Boolean)?) {
        inner.setPlaintextConfirmer(confirm)
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
        unscoped("select")

    override suspend fun unselect() {
        unscoped("unselect")
    }

    override suspend fun fetchIndex(request: IndexRequest): List<IndexRow> =
        unscoped("fetchIndex")

    override suspend fun fetchStructure(uid: Long): MimePart =
        unscoped("fetchStructure")

    override suspend fun fetchStructureAt(uid: Long, section: String): MimePart =
        unscoped("fetchStructureAt")

    override suspend fun peekPart(uid: Long, section: String, offset: Int, length: Int): ByteArray =
        unscoped("peekPart")

    override suspend fun fetchRfc822(uid: Long): ByteArray =
        unscoped("fetchRfc822")

    override suspend fun storeFlags(uids: List<Long>, add: Set<String>, remove: Set<String>) {
        unscoped("storeFlags")
    }

    override suspend fun storeFlagsAll(add: Set<String>, remove: Set<String>) {
        unscoped("storeFlagsAll")
    }

    override suspend fun uidExpungeDeleted() {
        unscoped("uidExpungeDeleted")
    }

    override suspend fun uidExpunge(uids: List<Long>) {
        unscoped("uidExpunge")
    }

    override suspend fun copyThenDelete(uids: List<Long>, targetMailbox: String) {
        unscoped("copyThenDelete")
    }

    override suspend fun copyUids(uids: List<Long>, targetMailbox: String) {
        unscoped("copyUids")
    }

    override suspend fun copyAllThenDelete(targetMailbox: String) {
        unscoped("copyAllThenDelete")
    }

    override suspend fun selectedExists(): Int = unscoped("selectedExists")

    override suspend fun takeCopiedUids(): List<Long> = unscoped("takeCopiedUids")
    override suspend fun copiedEpoch(): Long = unscoped("copiedEpoch")

    override suspend fun searchText(query: String): List<Long> =
        unscoped("searchText")

    override suspend fun searchCriterion(kind: String, argument: String): List<Long> =
        unscoped("searchCriterion")

    override suspend fun searchCount(kind: String, argument: String): Int =
        unscoped("searchCount")

    override suspend fun searchScope(scopeName: String, home: String, kind: String, argument: String): List<MailboxUids> =
        onLane { inner.searchScope(scopeName, home, kind, argument) }

    override suspend fun searchScopeCount(scopeName: String, home: String, kind: String, argument: String): Int =
        onLane { inner.searchScopeCount(scopeName, home, kind, argument) }

    override suspend fun subscribedMailboxes(): List<String> =
        onLane { inner.subscribedMailboxes() }

    override suspend fun searchStart(rule: StartRule, byUid: Boolean, edge: SearchEdge): List<Long> =
        unscoped("searchStart")

    override suspend fun locateUid(uid: Long): List<Long> = unscoped("locateUid")

    override suspend fun sort(key: SortKey, newestFirst: Boolean): List<Long> =
        unscoped("sort")

    override suspend fun sortWindow(key: SortKey, newestFirst: Boolean, first: Int, last: Int): UidWindow? =
        unscoped("sortWindow")

    override suspend fun searchWindow(kind: String, argument: String, first: Int, last: Int): UidWindow? =
        unscoped("searchWindow")

    override suspend fun thread(key: SortKey): ThreadNode =
        unscoped("thread")

    override suspend fun sortMatching(key: SortKey, newestFirst: Boolean, criteria: String): List<Long>? =
        unscoped("sortMatching")

    override suspend fun sortWindowMatching(
        key: SortKey,
        newestFirst: Boolean,
        first: Int,
        last: Int,
        criteria: String,
    ): UidWindow? = unscoped("sortWindowMatching")

    override suspend fun threadMatching(key: SortKey, criteria: String): ThreadNode? =
        unscoped("threadMatching")

    override suspend fun clientOrder(
        key: SortKey,
        newestFirst: Boolean,
        onProgress: (Int, Int) -> Unit,
        cancelled: () -> Boolean,
    ): List<Long> = unscoped("clientOrder")

    override suspend fun clientThread(key: SortKey): ThreadNode =
        unscoped("clientThread")

    /**
     * F13/F136/F27: watch setup (connect, TLS, login, EXAMINE, IDLE start) and stop do not take the lane.
     * The native generation orders a watch. The session lock is held only to publish or remove it.
     */
    override suspend fun watch(mailbox: String, onChange: (MailboxChange) -> Unit) {
        withContext(Dispatchers.IO) { inner.watch(mailbox, onChange) }
    }

    override suspend fun stopWatch() {
        withContext(Dispatchers.IO) { inner.stopWatch() }
    }

    override suspend fun noop() {
        onLane { inner.noop() }
    }

    override suspend fun knownTrash(): String = onLane { inner.knownTrash() }

    override suspend fun expungeOnLeave(mailbox: String) {
        onLane { inner.expungeOnLeave(mailbox) }
    }

    override suspend fun selectedInfo(): SelectResult = unscoped("selectedInfo")

    override suspend fun append(mailbox: String, rfc822: ByteArray, flags: Set<String>) {
        onLane { inner.append(mailbox, rfc822, flags) }
    }

    override suspend fun appendReturningUid(mailbox: String, rfc822: ByteArray, flags: Set<String>): Long =
        onLane { inner.appendReturningUid(mailbox, rfc822, flags) }

    override suspend fun smtpSend(rfc822: ByteArray, recipients: List<String>, dsnNotify: Int): Int =
        onLane { inner.smtpSend(rfc822, recipients, dsnNotify) }

    override suspend fun smtpSendFrom(rfc822: ByteArray, recipients: List<String>, dsnNotify: Int, envelopeFrom: String): Int =
        onLane { inner.smtpSendFrom(rfc822, recipients, dsnNotify, envelopeFrom) }

    override suspend fun smtpLoginCheck(): SmtpLoginCheck = onLane { inner.smtpLoginCheck() }

    override fun close() {
        inner.close()
        synchronized(laneLock) {
            laneDispatcher?.close()
            laneDispatcher = null
        }
    }

    /**
     * Lowest-layer serialisation (internal; nobody above coordinates on it). One lane call at a time, so the
     * select-or-verify and the command of an [inFolder] call can never be split by another caller.
     */
    private val laneMutex = Mutex()

    private class LaneHolder(val owner: SerialMailSession) : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<LaneHolder>
    }

    override suspend fun <T> inFolder(folder: FolderAt, fresh: Boolean, op: suspend (SelectResult, MailSession) -> T): T =
        onLane { op(selectFolderNow(inner, folder, fresh), inner) }

    /**
     * David, 2026-10-12: folder-dependent commands must name their folder ([inFolder], [at], [onRefs]). A bare
     * call would act in whatever folder the connection has selected, so it is refused.
     */
    private fun unscoped(name: String): Nothing =
        throw MailFailure("internal: $name without a folder; use inFolder/at")

    private suspend fun <T> onLane(block: suspend () -> T): T {
        // An op given to inFolder must use the session it is handed, never this one again (it would deadlock).
        check(coroutineContext[LaneHolder]?.owner !== this) { "re-entrant session call inside inFolder" }
        return laneMutex.withLock { val l = lane; withContext(l + LaneHolder(this)) { block() } }
    }
}
