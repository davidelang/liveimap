package org.dlang.liveimap.settings

import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.yield
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
import org.dlang.liveimap.session.SmtpLoginCheck
import org.dlang.liveimap.session.SortKey
import org.dlang.liveimap.session.ThreadNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountSetupProbeTest {
    @Test fun cancelDuringOpenClosesTheProbe() = runBlocking {
        val fake = HangingProbe()
        val settings = AccountSettings(smtpHost = "smtp.example")
        val probes = testAccountProbes("acct", settings, "pw", "pw", fake)
        val job = launch { testAccountLogins(settings, probes) }
        while (!fake.started) yield()
        job.cancelAndJoin()
        assertTrue(fake.closed)
    }

    @Test fun imapAuthFailureDoesNotCallSmtpWhenThePasswordIsShared() = runBlocking {
        var smtp = 0
        val probes = AccountProbes(
            imapLogin = { throw IllegalStateException("login failed: no") },
            smtpLogin = { smtp += 1; SmtpLoginCheck.LoggedIn },
            jmapLogin = {},
            smtpUsesImapPassword = true,
        )
        val lines = testAccountLogins(AccountSettings(smtpHost = "smtp.x", smtpSameAsImap = true), probes)
        assertEquals(0, smtp)
        assertTrue(lines[0].startsWith("FAIL IMAP login:"))
        assertTrue(lines[1].startsWith("FAIL SMTP login: not tried"))
    }

    @Test fun distinctSmtpPasswordIsStillTried() = runBlocking {
        var smtp = 0
        val probes = AccountProbes(
            imapLogin = { throw IllegalStateException("login failed: no") },
            smtpLogin = { smtp += 1; SmtpLoginCheck.LoggedIn },
            jmapLogin = {},
        )
        testAccountLogins(AccountSettings(smtpHost = "smtp.x"), probes)
        assertEquals(1, smtp)
    }

    @Test fun connectionFailureStillTriesSmtp() = runBlocking {
        var smtp = 0
        val probes = AccountProbes(
            imapLogin = { throw IllegalStateException("connection failed") },
            smtpLogin = { smtp += 1; SmtpLoginCheck.LoggedIn },
            jmapLogin = {},
            smtpUsesImapPassword = true,
        )
        testAccountLogins(AccountSettings(smtpHost = "smtp.x", smtpSameAsImap = true), probes)
        assertEquals(1, smtp)
    }

    @Test fun probePasswordFlagFollowsTheTickOrEqualText() {
        val fake = HangingProbe()
        assertTrue(testAccountProbes("a", AccountSettings(), "same", "same", fake).smtpUsesImapPassword)
        assertTrue(testAccountProbes("a", AccountSettings(smtpSameAsImap = true), "imap", "other", fake).smtpUsesImapPassword)
        assertFalse(testAccountProbes("a", AccountSettings(), "imap", "smtp", fake).smtpUsesImapPassword)
    }
}

/** openWith hangs until the caller is cancelled. Does not open a socket. */
private class HangingProbe : MailSession {
    @Volatile var started = false
    @Volatile var closed = false

    override val capabilities: Set<String> = emptySet()

    override suspend fun open(account: AccountSettings): OpenResult {
        started = true
        suspendCancellableCoroutine<Unit> { }
        return OpenResult.Connected
    }

    override suspend fun namespaces(): List<Namespace> = unused()
    override suspend fun listLevel(prefix: String, parentMailbox: String?, unreadCounts: Boolean): List<FolderEntry> = unused()
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
    override suspend fun smtpSend(rfc822: ByteArray, recipients: List<String>, dsnNotify: Int): Int = unused()
    override fun close() { closed = true }

    private fun unused(): Nothing = throw MailFailure("not used")
}
