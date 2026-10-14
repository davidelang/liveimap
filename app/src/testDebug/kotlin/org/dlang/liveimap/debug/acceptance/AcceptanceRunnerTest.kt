package org.dlang.liveimap.debug.acceptance

import kotlinx.coroutines.runBlocking
import org.dlang.liveimap.session.*
import org.dlang.liveimap.settings.AccountSettings
import org.dlang.liveimap.settings.SortKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AcceptanceRunnerTest {
    private fun run(user: String, caps: Set<String>, email: String = "liveimap@lab.test", host: String = "lab.test"): Pair<List<ReportRow>, FakeLab> {
        val fake = FakeLab(caps)
        val account = AccountSettings(username = user, email = email, imapHost = host)
        val rows = runBlocking {
            runAcceptance(AcceptanceContext(fake, account) { OpenResult.Connected }, acceptanceTests, readOnly = false, clock = { 0L }, readMarker = { SeedMarker(SeedVersion, false) })
        }
        return rows to fake
    }
    private fun List<ReportRow>.row(name: String) = first { it.name == name }

    @Test fun everyRegisteredTestGetsARowAndFailuresDoNotStopTheRun() {
        val (rows, _) = run("liveimap", setOf("IMAP4rev1"))
        assertEquals(acceptanceTests.size, rows.size)
        assertEquals("pass", rows.row("namespaces").result)
        assertEquals("fail", rows.row("list top level").result)
        assertEquals("not used", rows.row("list top level").reason)
        assertEquals("pass", rows.row("reconnect").result)
    }

    @Test fun missingCapabilitiesSkip() {
        val (rows, _) = run("liveimap", setOf("IMAP4rev1"))
        assertEquals("skip", rows.row("sort from").result)
        assertEquals("SORT not advertised", rows.row("sort from").reason)
        assertEquals("skip", rows.row("idle watch").result)
    }

    @Test fun realUserChangesNoMailAndSendsNothing() {
        val (rows, fake) = run("david", setOf("IMAP4rev1"))
        for (name in listOf("append to test folder", "flag and delete appended", "send to self")) {
            assertEquals("skip", rows.row(name).result)
            assertEquals("not a lab test user: no mail changed", rows.row(name).reason)
        }
        assertTrue(fake.calls.none { it.startsWith("append") || it.startsWith("send") })
    }

    @Test fun labUserSendsOnlyToItsOwnAddress() {
        val (rows, fake) = run("liveimap", setOf("IMAP4rev1"))
        assertEquals("pass", rows.row("send to self").result)
        assertEquals(listOf("send liveimap@lab.test"), fake.calls.filter { it.startsWith("send") })
        assertTrue("append LiveIMAP-Test" in fake.calls)
    }

    @Test fun noEmailMeansNoSend() {
        val (rows, fake) = run("liveimap", setOf("IMAP4rev1"), email = "")
        assertEquals("skip", rows.row("send to self").result)
        assertTrue(fake.calls.none { it.startsWith("send") })
    }

    @Test fun reportCarriesHeaderAndDurations() {
        val dir = kotlin.io.path.createTempDirectory("acc").toFile()
        val report = AcceptanceReport(AcceptanceSuite.Full, listOf(ReportRow("a", "f", listOf("p"), "pass", "", 12)), listOf("build" to "v1"))
        writeReport(dir, report, "20261012-142000")
        val json = java.io.File(dir, "acceptance-20261012-142000-Full.json").readText()
        assertTrue(json.startsWith("{\"build\":\"v1\","))
        assertTrue(json.contains("\"ms\":12"))
        assertTrue(java.io.File(dir, "acceptance-20261012-142000-Full.txt").readText().startsWith("# build: v1\n"))
        dir.deleteRecursively()
    }
}

internal class FakeLab(override val capabilities: Set<String>) : MailSession {
    val calls = ArrayList<String>()
    var cert: (suspend (CertPrompt) -> Boolean)? = null
    var plain: (suspend () -> Boolean)? = null
    var suspends: Int = 0


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

    override suspend fun namespaces(): List<Namespace> = emptyList()

    override suspend fun listLevel(
        prefix: String,
        parentMailbox: String?,
        unreadCounts: Boolean,
    ): List<FolderEntry> = unused()

    override suspend fun select(mailbox: String): SelectResult { calls += "select $mailbox"; return SelectResult(3, 0, 0) }

    override suspend fun unselect() { calls += "unselect" }

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

    override suspend fun append(mailbox: String, rfc822: ByteArray, flags: Set<String>) { calls += "append $mailbox" }
    override suspend fun mailboxListed(name: String): Boolean = true

    override suspend fun smtpSend(rfc822: ByteArray, recipients: List<String>, dsnNotify: Int): Int { calls += "send ${recipients.joinToString()}"; return 0 }

    override fun close() { calls += "close" }

    private fun unused(): Nothing = throw MailFailure("not used")
}
