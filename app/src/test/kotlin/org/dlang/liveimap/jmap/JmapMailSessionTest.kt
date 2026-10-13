package org.dlang.liveimap.jmap

import kotlinx.coroutines.runBlocking
import org.dlang.liveimap.session.IndexMode
import org.dlang.liveimap.session.IndexRequest
import org.dlang.liveimap.session.MailFailure
import org.dlang.liveimap.session.OpenResult
import org.dlang.liveimap.settings.AccountSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

class JmapMailSessionTest {
    private val session = JmapSession("u", "https://mail.example/api", "", "", "", "s", setOf(JMAP_MAIL), "A1")

    private class Fake(val replies: ArrayDeque<String>, val status: Int = 200) {
        val bodies = mutableListOf<String>()
        val pins = mutableListOf<String>()
        fun postFor(pin: String): (String, String, String) -> JmapHttpExchange = { _, body, _ ->
            pins += pin
            bodies += body
            val reply = replies.removeFirst()
            object : JmapHttpExchange {
                override val status = this@Fake.status
                override fun peerDer() = listOf(byteArrayOf(1))
                override fun header(name: String): String? = null
                override fun body() = reply
                override fun close() {}
            }
        }
    }

    private fun open(fake: Fake, account: AccountSettings = AccountSettings(username = "u", certPin = "imap", jmapCertPin = "jm")): JmapMailSession {
        val s = JmapMailSession({ JmapOffer.Mail(session) }, { "pw" }, fake::postFor, { _, _, _ -> "" }, { 42 })
        assertEquals(OpenResult.Connected, runBlocking { s.open(account) })
        return s
    }

    private val level = """{"methodResponses":[["Mailbox/get",{"list":[{"id":"m1","name":"INBOX","role":"inbox","totalEmails":2,"unreadEmails":1},{"id":"m2","name":"Trash","role":"trash","totalEmails":0,"unreadEmails":0}]},"0"]]}"""
    private val noKids = """{"methodResponses":[["Mailbox/get",{"list":[]},"0"]]}"""
    private val total = """{"methodResponses":[["Mailbox/get",{"list":[{"id":"m1","totalEmails":2}]},"0"]]}"""
    private val emails = """{"methodResponses":[["Email/query",{"ids":["e1","e2"]},"0"],["Email/get",{"list":[{"id":"e1","keywords":{"${'$'}seen":true},"size":10,"receivedAt":"2026-10-13T00:00:00Z","subject":"a","from":[{"email":"x@y"}]},{"id":"e2","keywords":{},"subject":"b"}]},"1"]]}"""

    @Test
    fun listSelectFetchUsesTheJmapPinAndNumbersUids() = runBlocking {
        val fake = Fake(ArrayDeque(listOf(level, noKids, total, emails)))
        val s = open(fake)
        val folders = s.listLevel("", null, true)
        assertEquals(listOf("INBOX", "Trash"), folders.map { it.mailbox })
        assertEquals("\\Trash", folders[1].specialUse)
        assertEquals("Trash", s.knownTrash())
        val sel = s.select("INBOX")
        assertEquals(42L, sel.uidValidity)
        assertEquals(2, sel.exists)
        val rows = s.fetchIndex(IndexRequest("INBOX", IndexMode.ArrivalNewest, limit = 2))
        assertEquals(listOf(1L, 2L), rows.map { it.uid })
        assertEquals(setOf("\\Seen"), rows[0].flags)
        assertEquals("x@y", rows[0].from)
        assertTrue(fake.pins.all { it == "jm" }) // F63: never the IMAP pin
        assertTrue(fake.bodies.last().contains("\"isAscending\":false"))
    }

    @Test
    fun flagsMapToKeywordsAndDeletedIsRefused() = runBlocking {
        val set = """{"methodResponses":[["Email/set",{"updated":{"e1":null}},"0"]]}"""
        val fake = Fake(ArrayDeque(listOf(level, noKids, total, emails, set)))
        val s = open(fake)
        s.listLevel("", null, false); s.select("INBOX")
        s.fetchIndex(IndexRequest("INBOX", IndexMode.ArrivalNewest, limit = 2))
        s.storeFlags(listOf(1L), setOf("\\Flagged"), setOf("\\Seen"))
        assertTrue(fake.bodies.last().contains("\"keywords/${'$'}flagged\":true"))
        assertTrue(fake.bodies.last().contains("\"keywords/${'$'}seen\":null"))
        try { s.storeFlags(listOf(1L), setOf("\\Deleted"), emptySet()); fail() } catch (_: MailFailure) {}
    }

    @Test
    fun moveRewritesMailboxIdsAndNotUpdatedFails() = runBlocking {
        val notSet = """{"methodResponses":[["Email/set",{"notUpdated":{"e1":{"type":"forbidden"}}},"0"]]}"""
        val fake = Fake(ArrayDeque(listOf(level, noKids, total, emails, notSet)))
        val s = open(fake)
        s.listLevel("", null, false); s.select("INBOX")
        s.fetchIndex(IndexRequest("INBOX", IndexMode.ArrivalNewest, limit = 2))
        try { s.copyThenDelete(listOf(1L), "Trash"); fail() } catch (e: MailFailure) { assertTrue(e.text.contains("forbidden")) }
        assertTrue(fake.bodies.last().contains("\"mailboxIds/m1\":null,\"mailboxIds/m2\":true"))
    }

    @Test
    fun methodErrorsBadJsonAndNetworkFailuresAreMailFailures() = runBlocking {
        for (reply in listOf("""{"methodResponses":[["error",{"type":"serverFail"},"0"]]}""", "[[[", "{")) {
            val s = open(Fake(ArrayDeque(listOf(level, noKids, reply))))
            s.listLevel("", null, false)
            try { s.select("INBOX"); fail(reply) } catch (_: MailFailure) {}
        }
        val s = JmapMailSession({ JmapOffer.Mail(session) }, { "pw" }, { { _, _, _ -> throw IOException("reset") } }, { _, _, _ -> "" })
        s.open(AccountSettings(username = "u"))
        try { s.listLevel("", null, false); fail() } catch (_: MailFailure) {}
    }

    @Test
    fun noJmapOfferFailsOpenAndUnlistedFolderFails() = runBlocking {
        val s = JmapMailSession({ JmapOffer.None }, { "pw" })
        assertTrue(s.open(AccountSettings(username = "u")) is OpenResult.Failed)
        val t = open(Fake(ArrayDeque()))
        try { t.select("Nope"); fail() } catch (_: MailFailure) {}
    }

    @Test
    fun quotingEscapesControlAndQuotes() {
        assertEquals("\"a\\\"b\\\\c\\u000a\"", JmapMailSession.q("a\"b\\c\n"))
    }
}
