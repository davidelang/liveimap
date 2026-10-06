package org.dlang.liveimap.session

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.dlang.liveimap.settings.AccountSettings
import org.dlang.liveimap.settings.SortKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SerialMailSessionTest {
    @Test
    fun overlap() = runBlocking {
        val inner = OverlapInner()
        val session = SerialMailSession(inner)
        try {
            val first = async(Dispatchers.Default) { session.open(AccountSettings()) }
            assertTrue(inner.entered.await(5, TimeUnit.SECONDS))
            val second = async(Dispatchers.Default) { session.open(AccountSettings()) }
            Thread.sleep(300)
            assertEquals(listOf("liveimap-imap"), inner.names().map { imapThread(it) })
            assertEquals(1, inner.maxDepth.get())
            inner.release.countDown()
            assertEquals(OpenResult.Connected, first.await())
            assertEquals(OpenResult.Connected, second.await())
            assertEquals(listOf("liveimap-imap", "liveimap-imap"), inner.names().map { imapThread(it) })
            assertEquals(1, inner.maxDepth.get())
        } finally {
            inner.release.countDown()
        }
    }

    @Test
    fun appendReturningUidReturnsInnerUidOnTheLane() = runBlocking {
        val inner = AppendUidInner()
        val session = SerialMailSession(inner)
        val bytes = byteArrayOf(1, 2, 3)
        val flags = setOf("\\Draft")
        val uid = session.appendReturningUid("Postponed", bytes, flags)
        assertEquals(3955L, uid)
        assertEquals(1, inner.uidCalls.get())
        assertEquals(0, inner.appendCalls.get())
        assertEquals("Postponed", inner.mailbox)
        assertTrue(bytes.contentEquals(inner.rfc822))
        assertEquals(flags, inner.flags)
        assertEquals(listOf("liveimap-imap"), inner.laneNames().map { imapThread(it) })
    }
}

private fun imapThread(name: String): String {
    val suffix = name.indexOf(" @coroutine#")
    return if (suffix < 0) name else name.substring(0, suffix)
}

private class OverlapInner : MailSession {
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)
    val maxDepth = AtomicInteger(0)
    private val depth = AtomicInteger(0)
    private val seen = mutableListOf<String>()

    override val capabilities: Set<String> = emptySet()

    fun names(): List<String> = synchronized(seen) { seen.toList() }

    override suspend fun open(account: AccountSettings): OpenResult {
        val now = depth.incrementAndGet()
        maxDepth.updateAndGet { current -> if (now > current) now else current }
        synchronized(seen) { seen.add(Thread.currentThread().name) }
        try {
            if (now == 1) {
                entered.countDown()
                if (!release.await(5, TimeUnit.SECONDS)) {
                    throw MailFailure("release timed out")
                }
            }
            return OpenResult.Connected
        } finally {
            depth.decrementAndGet()
        }
    }

    override suspend fun namespaces(): List<Namespace> = unused()

    override suspend fun listLevel(
        prefix: String,
        parentMailbox: String?,
        unreadCounts: Boolean,
    ): List<FolderEntry> = unused()

    override suspend fun select(mailbox: String): SelectResult = unused()

    override suspend fun unselect() = unused()

    override suspend fun fetchIndex(request: IndexRequest): List<IndexRow> = unused()

    override suspend fun fetchStructure(uid: Long): MimePart = unused()

    override suspend fun peekPart(uid: Long, section: String, offset: Int, length: Int): ByteArray = unused()

    override suspend fun fetchRfc822(uid: Long): ByteArray = unused()

    override suspend fun storeFlags(uids: List<Long>, add: Set<String>, remove: Set<String>) = unused()

    override suspend fun uidExpungeDeleted() = unused()

    override suspend fun copyThenDelete(uids: List<Long>, targetMailbox: String) = unused()

    override suspend fun searchText(query: String): List<Long> = unused()

    override suspend fun searchCriterion(kind: String, argument: String): List<Long> = unused()

    override suspend fun sort(key: SortKey, newestFirst: Boolean): List<Long> = unused()

    override suspend fun thread(key: SortKey): ThreadNode = unused()

    override suspend fun watch(mailbox: String, onChange: (MailboxChange) -> Unit) = unused()

    override suspend fun stopWatch() = unused()

    override suspend fun append(mailbox: String, rfc822: ByteArray, flags: Set<String>) = unused()

    override suspend fun smtpSend(rfc822: ByteArray, recipients: List<String>) = unused()

    override fun close() = Unit

    private fun unused(): Nothing = throw MailFailure("not used")
}

private class AppendUidInner : MailSession {
    val uidCalls = AtomicInteger(0)
    val appendCalls = AtomicInteger(0)
    var mailbox: String = ""
    var rfc822: ByteArray = ByteArray(0)
    var flags: Set<String> = emptySet()
    private val lanes = mutableListOf<String>()

    override val capabilities: Set<String> = emptySet()

    fun laneNames(): List<String> = synchronized(lanes) { lanes.toList() }

    override suspend fun open(account: AccountSettings): OpenResult = unused()

    override suspend fun namespaces(): List<Namespace> = unused()

    override suspend fun listLevel(
        prefix: String,
        parentMailbox: String?,
        unreadCounts: Boolean,
    ): List<FolderEntry> = unused()

    override suspend fun select(mailbox: String): SelectResult = unused()

    override suspend fun unselect() = unused()

    override suspend fun fetchIndex(request: IndexRequest): List<IndexRow> = unused()

    override suspend fun fetchStructure(uid: Long): MimePart = unused()

    override suspend fun peekPart(uid: Long, section: String, offset: Int, length: Int): ByteArray = unused()

    override suspend fun fetchRfc822(uid: Long): ByteArray = unused()

    override suspend fun storeFlags(uids: List<Long>, add: Set<String>, remove: Set<String>) = unused()

    override suspend fun uidExpungeDeleted() = unused()

    override suspend fun copyThenDelete(uids: List<Long>, targetMailbox: String) = unused()

    override suspend fun searchText(query: String): List<Long> = unused()

    override suspend fun searchCriterion(kind: String, argument: String): List<Long> = unused()

    override suspend fun sort(key: SortKey, newestFirst: Boolean): List<Long> = unused()

    override suspend fun thread(key: SortKey): ThreadNode = unused()

    override suspend fun watch(mailbox: String, onChange: (MailboxChange) -> Unit) = unused()

    override suspend fun stopWatch() = unused()

    override suspend fun append(mailbox: String, rfc822: ByteArray, flags: Set<String>) {
        appendCalls.incrementAndGet()
    }

    override suspend fun appendReturningUid(
        mailbox: String,
        rfc822: ByteArray,
        flags: Set<String>,
    ): Long {
        uidCalls.incrementAndGet()
        this.mailbox = mailbox
        this.rfc822 = rfc822
        this.flags = flags
        synchronized(lanes) { lanes.add(Thread.currentThread().name) }
        return 3955
    }

    override suspend fun smtpSend(rfc822: ByteArray, recipients: List<String>) = unused()

    override fun close() = Unit

    private fun unused(): Nothing = throw MailFailure("not used")
}
