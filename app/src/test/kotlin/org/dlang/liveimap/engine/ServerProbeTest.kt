package org.dlang.liveimap.engine

import org.dlang.liveimap.session.FolderEntry
import org.dlang.liveimap.session.IndexRequest
import org.dlang.liveimap.session.IndexRow
import org.dlang.liveimap.session.MailFailure
import org.dlang.liveimap.session.MailSession
import org.dlang.liveimap.session.MailboxChange
import org.dlang.liveimap.session.MimePart
import org.dlang.liveimap.session.Namespace
import org.dlang.liveimap.session.OpenResult
import org.dlang.liveimap.session.SelectResult
import org.dlang.liveimap.session.ThreadNode
import org.dlang.liveimap.settings.AccountSettings
import org.dlang.liveimap.settings.SortKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

class ServerProbeTest {
    @Test
    fun listSyntaxFailureIsReportedForListAndListExtended() {
        val capabilities = (requiredCapabilities + listOf("LIST-EXTENDED", "LIST-STATUS")).toSet()
        val session = ProbeMailSession(
            capabilities = capabilities,
            openResult = OpenResult.Connected,
        )
        val report = runImmediate { probeServer(session, AccountSettings()) }
        assertTrue(report.contains("FAIL LIST: invalid syntax in list command"))
        assertTrue(report.contains("FAIL LIST-EXTENDED: invalid syntax in list command"))
        assertTrue(report.contains("FAIL LIST-STATUS: invalid syntax in list command"))
        assertTrue(report.contains("FAIL CHILDREN: invalid syntax in list command"))
        assertEquals(1, session.listCalls)
        assertEquals("", session.listPrefix)
        assertNull(session.listParent)
        assertEquals(listOf("INBOX"), session.selectedMailboxes)
        assertEquals(listOf(SortKey.From to true), session.sortCalls)
        assertFalse(session.sortCalls.any { it.first == SortKey.Arrival })
        assertEquals(listOf("x"), session.searchQueries)
        assertEquals(listOf("INBOX"), session.watched)
        assertEquals(1, session.stopWatchCalls)
        assertEquals(0, session.forbidden)
        assertTrue(report.none { it.contains("password", ignoreCase = true) })
    }

    @Test
    fun failedOpenIsOnlyTheLoginLine() {
        val session = ProbeMailSession(
            openResult = OpenResult.Failed("connection refused"),
        )
        val report = runImmediate { probeServer(session, AccountSettings()) }
        assertEquals(listOf("FAIL login: connection refused"), report)
        assertEquals(0, session.listCalls)
        assertEquals(0, session.forbidden)
    }
}

private class ProbeMailSession(
    override val capabilities: Set<String> = emptySet(),
    private val openResult: OpenResult,
) : MailSession {
    var listCalls: Int = 0
    var listPrefix: String = ""
    var listParent: String? = null
    val selectedMailboxes = mutableListOf<String>()
    val sortCalls = mutableListOf<Pair<SortKey, Boolean>>()
    val searchQueries = mutableListOf<String>()
    val watched = mutableListOf<String>()
    var stopWatchCalls: Int = 0
    var forbidden: Int = 0

    override suspend fun open(account: AccountSettings): OpenResult = openResult

    override suspend fun namespaces(): List<Namespace> = emptyList()

    override suspend fun listLevel(prefix: String, parentMailbox: String?, unreadCounts: Boolean): List<FolderEntry> {
        listCalls += 1
        listPrefix = prefix
        listParent = parentMailbox
        throw MailFailure("invalid syntax in list command")
    }

    override suspend fun select(mailbox: String): SelectResult {
        selectedMailboxes += mailbox
        return SelectResult(1, 1, 0)
    }

    override suspend fun unselect() = Unit

    override suspend fun fetchIndex(request: IndexRequest): List<IndexRow> = refuse("fetchIndex")

    override suspend fun fetchStructure(uid: Long): MimePart = refuse("fetchStructure")

    override suspend fun peekPart(uid: Long, section: String, offset: Int, length: Int): ByteArray =
        refuse("peekPart")

    override suspend fun fetchRfc822(uid: Long): ByteArray = refuse("fetchRfc822")

    override suspend fun storeFlags(uids: List<Long>, add: Set<String>, remove: Set<String>) {
        refuse("storeFlags")
    }

    override suspend fun uidExpungeDeleted() {
        refuse("uidExpungeDeleted")
    }

    override suspend fun copyThenDelete(uids: List<Long>, targetMailbox: String) {
        refuse("copyThenDelete")
    }

    override suspend fun searchText(query: String): List<Long> {
        searchQueries += query
        return emptyList()
    }

    override suspend fun searchCriterion(kind: String, argument: String): List<Long> {
        searchQueries += argument
        return emptyList()
    }

    override suspend fun sort(key: SortKey, newestFirst: Boolean): List<Long> {
        sortCalls += key to newestFirst
        return emptyList()
    }

    override suspend fun thread(key: SortKey): ThreadNode = ThreadNode(null, emptyList())

    override suspend fun watch(mailbox: String, onChange: (MailboxChange) -> Unit) {
        watched += mailbox
    }

    override suspend fun stopWatch() {
        stopWatchCalls += 1
    }

    override suspend fun append(mailbox: String, rfc822: ByteArray) {
        refuse("append")
    }

    override suspend fun smtpSend(rfc822: ByteArray, recipients: List<String>) {
        refuse("smtpSend")
    }

    override fun close() = Unit

    private fun refuse(name: String): Nothing {
        forbidden += 1
        error(name)
    }
}

private fun <T> runImmediate(block: suspend () -> T): T {
    var result: Result<T>? = null
    block.startCoroutine(Continuation(EmptyCoroutineContext) { result = it })
    return result!!.getOrThrow()
}
