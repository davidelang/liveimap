package org.dlang.liveimap.session

import kotlinx.coroutines.runBlocking
import org.dlang.liveimap.settings.AccountSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private class Rec(val name: String, val log: MutableList<String>, val openResult: OpenResult = OpenResult.Connected) :
    MailSession by DisconnectedMailSession() {
    override val capabilities: Set<String> get() = setOf(name)
    override suspend fun open(account: AccountSettings): OpenResult { log += "$name open"; return openResult }
    override suspend fun select(mailbox: String): SelectResult { log += "$name select $mailbox"; return SelectResult(1, 1, 0) }
    override suspend fun smtpSendFrom(rfc822: ByteArray, recipients: List<String>, dsnNotify: Int, envelopeFrom: String): Int {
        log += "$name send"; return 0
    }
    override suspend fun smtpSend(rfc822: ByteArray, recipients: List<String>, dsnNotify: Int): Int = smtpSendFrom(rfc822, recipients, dsnNotify, "")
    override fun setCertConfirmer(confirm: (suspend (CertPrompt) -> Boolean)?) { if (confirm != null) log += "$name confirmer true" }
    override fun close() { log += "$name close" }
}

class RoutedMailSessionTest {
    private val log = mutableListOf<String>()
    private fun routed(imapOpen: OpenResult = OpenResult.Connected) =
        RoutedMailSession({ Rec("imap", log, imapOpen) }, { Rec("jmap", log) })

    @Test
    fun imapByDefaultJmapOnlyWhenTurnedOn() = runBlocking {
        val r = routed()
        r.open(AccountSettings(username = "u"))
        assertFalse(r.routedToJmap)
        assertEquals(setOf("imap"), r.capabilities)
        r.open(AccountSettings(username = "u", jmapFolders = true))
        assertTrue(r.routedToJmap)
        r.select("INBOX")
        assertEquals(listOf("imap open", "imap close", "jmap open", "jmap select INBOX"), log)
    }

    @Test
    fun switchingBackClosesJmap() = runBlocking {
        val r = routed()
        r.open(AccountSettings(jmapFolders = true))
        r.open(AccountSettings())
        assertEquals(listOf("jmap open", "jmap close", "imap open"), log)
    }

    @Test
    fun jmapAccountSendsOverImapOpenedOnceOnDemand() = runBlocking {
        val r = routed()
        val account = AccountSettings(username = "u", jmapFolders = true)
        r.open(account)
        r.smtpSendFrom(byteArrayOf(1), listOf("a@b"), 0, "")
        r.smtpSend(byteArrayOf(1), listOf("a@b"))
        assertEquals(listOf("jmap open", "imap open", "imap send", "imap send"), log)
        assertTrue(r.routedToJmap)
    }

    @Test
    fun sendFailsCleanlyWhenImapCannotOpen() = runBlocking {
        val r = routed(OpenResult.Failed("no imap"))
        r.open(AccountSettings(jmapFolders = true))
        try { r.smtpSend(byteArrayOf(1), listOf("a@b")); throw AssertionError("sent") } catch (e: MailFailure) {
            assertEquals("no imap", e.text)
        }
    }

    @Test
    fun confirmersReachSessionsMadeLater() = runBlocking {
        val r = routed()
        r.setCertConfirmer { true }
        r.open(AccountSettings(jmapFolders = true))
        assertEquals(listOf("jmap confirmer true", "jmap open"), log)
    }

    @Test
    fun closeClosesBoth() = runBlocking {
        val r = routed()
        r.open(AccountSettings(jmapFolders = true))
        r.smtpSend(byteArrayOf(1), listOf("a@b"))
        log.clear()
        r.close()
        assertEquals(listOf("imap close", "jmap close"), log)
    }
}
