package org.dlang.liveimap.session

import kotlinx.coroutines.runBlocking
import org.dlang.liveimap.settings.AccountSettings
import org.dlang.liveimap.settings.SortKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class SessionTableTest {
    @Test
    fun sameIdReturnsSameInstance() {
        var calls = 0
        val table = SessionTable {
            calls += 1
            RecordingSession()
        }
        val first = table.session("a")
        val second = table.session("a")
        val other = table.session("b")
        assertSame(first, second)
        assertNotSame(first, other)
        assertEquals(2, calls)
    }

    @Test
    fun blankIdDoesNotCallFactory() {
        var calls = 0
        val table = SessionTable {
            calls += 1
            RecordingSession()
        }
        assertThrows(IllegalArgumentException::class.java) {
            table.session("")
        }
        assertEquals(0, calls)
    }

    @Test
    fun certConfirmerReachesStoredAndLater() {
        val table = SessionTable { RecordingSession() }
        val stored = table.session("a") as RecordingSession
        val confirm: suspend (CertPrompt) -> Boolean = { true }
        table.setCertConfirmer(confirm)
        val later = table.session("b") as RecordingSession
        assertSame(confirm, stored.cert)
        assertSame(confirm, later.cert)
    }

    @Test
    fun plaintextConfirmerReachesStoredAndLater() {
        val table = SessionTable { RecordingSession() }
        val stored = table.session("a") as RecordingSession
        val confirm: suspend () -> Boolean = { false }
        table.setPlaintextConfirmer(confirm)
        val later = table.session("b") as RecordingSession
        assertSame(confirm, stored.plain)
        assertSame(confirm, later.plain)
    }

    @Test
    fun suspendConnectionsCallsStoredSessionsOnly() = runBlocking {
        var calls = 0
        val table = SessionTable {
            calls += 1
            RecordingSession()
        }
        val a = table.session("a") as RecordingSession
        val b = table.session("b") as RecordingSession
        val made = calls
        table.suspendConnections()
        assertEquals(1, a.suspends)
        assertEquals(1, b.suspends)
        assertEquals(made, calls)
    }
}

private class RecordingSession : MailSession {
    var cert: (suspend (CertPrompt) -> Boolean)? = null
    var plain: (suspend () -> Boolean)? = null
    var suspends: Int = 0

    override val capabilities: Set<String> = emptySet()

    override suspend fun suspendConnections() {
        suspends += 1
    }

    override fun setCertConfirmer(confirm: (suspend (CertPrompt) -> Boolean)?) {
        cert = confirm
    }

    override fun setPlaintextConfirmer(confirm: (suspend () -> Boolean)?) {
        plain = confirm
    }

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

    override suspend fun append(mailbox: String, rfc822: ByteArray, flags: Set<String>) = unused()

    override suspend fun smtpSend(rfc822: ByteArray, recipients: List<String>) = unused()

    override fun close() = Unit

    private fun unused(): Nothing = throw MailFailure("not used")
}
