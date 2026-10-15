package org.dlang.liveimap.jmap

import kotlinx.coroutines.runBlocking
import org.dlang.liveimap.engine.TlsPolicy
import org.dlang.liveimap.session.IndexMode
import org.dlang.liveimap.session.IndexRequest
import org.dlang.liveimap.session.MailFailure
import org.dlang.liveimap.session.OpenResult
import org.dlang.liveimap.settings.AccountSettings
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

class JmapMailSessionTest {
    private val session = JmapSession("u", "https://mail.example/api", "https://mail.example/dl/{accountId}/{blobId}?type={type}", "https://mail.example/up/{accountId}", "https://mail.example/events", "s", setOf(JMAP_MAIL), "A1")

    private class Fake(val replies: ArrayDeque<String>, val status: Int = 200) {
        val bodies = mutableListOf<String>()
        val pins = mutableListOf<String>()
        fun postFor(pin: String, policy: TlsPolicy): (String, String, String) -> JmapHttpExchange = { _, body, _ ->
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
        val s = JmapMailSession({ _, _ -> JmapOffer.Mail(session) }, { "pw" }, fake::postFor, { _, _, _ -> "" }, { 42 })
        assertEquals(OpenResult.Connected, runBlocking { s.open(account) })
        return s
    }

    private val level = """{"methodResponses":[["Mailbox/get",{"list":[{"id":"m1","name":"INBOX","role":"inbox","totalEmails":2,"unreadEmails":1},{"id":"m2","name":"Trash","role":"trash","totalEmails":0,"unreadEmails":0}]},"0"]]}"""
    private val noKids = """{"methodResponses":[["Mailbox/get",{"list":[]},"0"]]}"""
    private val total = """{"methodResponses":[["Mailbox/get",{"list":[{"id":"m1","totalEmails":2}]},"0"]]}"""
    private val emails = """{"methodResponses":[["Email/query",{"ids":["e1","e2"]},"0"],["Email/get",{"list":[{"id":"e1","keywords":{"${'$'}seen":true},"size":10,"receivedAt":"2026-10-13T00:00:00Z","subject":"a","from":[{"email":"x@y"}]},{"id":"e2","keywords":{},"subject":"b"}]},"1"]]}"""
    /** Id query for the INBOX fixtures, e1 then e2. No Email/get. */
    private val existingIds = """{"methodResponses":[["Email/query",{"ids":["e1","e2"],"total":2},"0"]]}"""
    /** Numbering query with no ids. A stored total of 0 still needs this response. */
    private val noExistingIds = """{"methodResponses":[["Email/query",{"ids":[]},"0"]]}"""
    /** The id query lists e1 then e2, so those stay the UIDs. */
    private val e1Uid = 1L
    private val e2Uid = 2L

    @Test
    fun listSelectFetchUsesTheJmapPinAndNumbersUids() = runBlocking {
        val fake = Fake(ArrayDeque(listOf(level, noKids, total, existingIds, emails)))
        val s = open(fake)
        val folders = s.listLevel("", null, true)
        assertEquals(listOf("INBOX", "Trash"), folders.map { it.mailbox })
        assertEquals("\\Trash", folders[1].specialUse)
        assertEquals("Trash", s.knownTrash())
        val sel = s.select("INBOX")
        assertEquals(42L, sel.uidValidity)
        assertEquals(3L, sel.uidNext)
        assertEquals(2, sel.exists)
        val idOnly = fake.bodies.filter { it.contains("Email/query") && !it.contains("Email/get") }
        assertEquals(1, idOnly.size)
        assertTrue(idOnly[0].contains("\"receivedAt\""))
        assertTrue(idOnly[0].contains("\"isAscending\":true"))
        val rows = s.fetchIndex(IndexRequest("INBOX", IndexMode.ArrivalNewest, limit = 2))
        assertEquals(listOf(e1Uid, e2Uid), rows.map { it.uid })
        assertEquals(setOf("\\Seen"), rows[0].flags)
        assertEquals("x@y", rows[0].from)
        assertTrue(fake.pins.all { it == "jm" }) // F63: never the IMAP pin
        assertTrue(fake.bodies.last().contains("\"isAscending\":false"))
    }

    @Test
    fun existingMailIsNumberedBeforeUidNextAndALateIdStaysThere() = runBlocking {
        val numbered = """{"methodResponses":[["Email/query",{"ids":["old","new"],"total":2},"0"]]}"""
        val newest = """{"methodResponses":[["Email/query",{"ids":["new","old"]},"0"],["Email/get",{"list":[{"id":"new","receivedAt":"2026-10-14T00:00:00Z"},{"id":"old","receivedAt":"2026-10-01T00:00:00Z"}]},"1"]]}"""
        val late = """{"methodResponses":[["Email/query",{"ids":["late"]},"0"],["Email/get",{"list":[{"id":"late","receivedAt":"2020-01-01T00:00:00Z"}]},"1"]]}"""
        val fake = Fake(ArrayDeque(listOf(level, noKids, total, numbered, newest, total, late)))
        val s = open(fake)
        s.listLevel("", null, false)
        val sel = s.select("INBOX")
        assertEquals(3L, sel.uidNext)
        val queries = fake.bodies.filter { it.contains("Email/query") }
        assertEquals(1, queries.size)
        assertTrue(!queries[0].contains("Email/get"))
        assertTrue(queries[0].contains("\"receivedAt\""))
        assertTrue(queries[0].contains("\"isAscending\":true"))
        assertTrue(queries[0].contains("\"calculateTotal\":true"))
        assertTrue(queries[0].contains("\"limit\":120"))
        assertTrue(!queries[0].contains("\"position\""))
        val rows = s.fetchIndex(IndexRequest("INBOX", IndexMode.ArrivalNewest, limit = 2))
        assertEquals(listOf(2L, 1L), rows.map { it.uid })
        val mark = fake.bodies.size
        s.select("INBOX")
        assertTrue(fake.bodies.drop(mark).none { it.contains("Email/query") })
        val arrived = s.fetchIndex(IndexRequest("INBOX", IndexMode.ArrivalOldest, limit = 1))
        assertEquals(listOf(3L), arrived.map { it.uid })
    }

    @Test
    fun flagsMapToKeywordsAndDeletedIsRefused() = runBlocking {
        val set = """{"methodResponses":[["Email/set",{"updated":{"e1":null}},"0"]]}"""
        val fake = Fake(ArrayDeque(listOf(level, noKids, total, existingIds, emails, set)))
        val s = open(fake)
        s.listLevel("", null, false); s.select("INBOX")
        s.fetchIndex(IndexRequest("INBOX", IndexMode.ArrivalNewest, limit = 2))
        s.storeFlags(listOf(e1Uid), setOf("\\Flagged"), setOf("\\Seen"))
        assertTrue(fake.bodies.last().contains("\"keywords/${'$'}flagged\":true"))
        assertTrue(fake.bodies.last().contains("\"keywords/${'$'}seen\":null"))
        try { s.storeFlags(listOf(e1Uid), setOf("\\Deleted"), emptySet()); fail() } catch (_: MailFailure) {}
    }

    @Test
    fun moveRewritesMailboxIdsAndNotUpdatedFails() = runBlocking {
        val notSet = """{"methodResponses":[["Email/set",{"notUpdated":{"e1":{"type":"forbidden"}}},"0"]]}"""
        val fake = Fake(ArrayDeque(listOf(level, noKids, total, existingIds, emails, notSet)))
        val s = open(fake)
        s.listLevel("", null, false); s.select("INBOX")
        s.fetchIndex(IndexRequest("INBOX", IndexMode.ArrivalNewest, limit = 2))
        try { s.copyThenDelete(listOf(e1Uid), "Trash"); fail() } catch (e: MailFailure) { assertTrue(e.text.contains("forbidden")) }
        assertTrue(fake.bodies.last().contains("\"mailboxIds/m1\":null,\"mailboxIds/m2\":true"))
    }

    @Test
    fun methodErrorsBadJsonAndNetworkFailuresAreMailFailures() = runBlocking {
        for (reply in listOf("""{"methodResponses":[["error",{"type":"serverFail"},"0"]]}""", "[[[", "{")) {
            val fake = Fake(ArrayDeque(listOf(level, noKids, reply)))
            val s = open(fake)
            s.listLevel("", null, false)
            try { s.select("INBOX"); fail(reply) } catch (_: MailFailure) {}
            assertTrue(fake.bodies.none { it.contains("Email/query") })
        }
        val s = JmapMailSession({ _, _ -> JmapOffer.Mail(session) }, { "pw" }, { _, _ -> { _, _, _ -> throw IOException("reset") } }, { _, _, _ -> "" })
        s.open(AccountSettings(username = "u"))
        try { s.listLevel("", null, false); fail() } catch (_: MailFailure) {}
    }

    @Test
    fun noJmapOfferFailsOpenAndUnlistedFolderFails() = runBlocking {
        val s = JmapMailSession({ _, _ -> JmapOffer.None }, { "pw" })
        assertTrue(s.open(AccountSettings(username = "u")) is OpenResult.Failed)
        val t = open(Fake(ArrayDeque(listOf(level, noKids))))
        try { t.select("Nope"); fail() } catch (_: MailFailure) {}
    }

    @Test
    fun quotingEscapesControlAndQuotes() {
        assertEquals("\"a\\\"b\\\\c\\u000a\"", JmapMailSession.q("a\"b\\c\n"))
    }

    @Test
    fun arrivalUidKeepsTheFirstAssignment() {
        val t = JmapMailSession.Uids()
        val start = t.next
        assertEquals(start, t.uid("n", 200L))
        assertEquals(start + 1, t.next)
        val left = t.next
        val older = t.uid("o", 100L)
        val same = t.uid("s", 200L)
        assertTrue(older >= left && same >= left && older != same)
        assertTrue(t.next > older && t.next > same)
        assertEquals(start, t.uid("n", 200L))
        assertEquals(older, t.uid("o", 100L))
        assertEquals(same, t.uid("s", 200L))
    }

    @Test
    fun shortPageIsNotTheWholeFolder() = runBlocking {
        val one = """{"methodResponses":[["Email/query",{"ids":["a"],"total":2},"0"],["Email/get",{"list":[{"id":"a","receivedAt":"1970-01-01T00:03:20Z"}]},"1"]]}"""
        val two = """{"methodResponses":[["Email/query",{"ids":["b"],"total":2},"0"],["Email/get",{"list":[{"id":"b","receivedAt":"1970-01-01T00:01:40Z"}]},"1"]]}"""
        val paged = Fake(ArrayDeque(listOf(level, noKids, total, noExistingIds, one, two)))
        val pagedSession = open(paged)
        pagedSession.listLevel("", null, false)
        pagedSession.select("INBOX")
        val from = paged.bodies.size
        val both = pagedSession.sort(org.dlang.liveimap.settings.SortKey.Arrival, true)
        val calls = paged.bodies.drop(from)
        assertEquals(2, calls.size)
        assertTrue(!calls[0].contains("\"position\""))
        assertTrue(calls[1].contains("\"position\":1"))
        assertEquals(listOf(1L, 2L), both)
        val whole = """{"methodResponses":[["Email/query",{"ids":["a","b"]},"0"],["Email/get",{"list":[{"id":"a","receivedAt":"1970-01-01T00:03:20Z"},{"id":"b","receivedAt":"1970-01-01T00:01:40Z"}]},"1"]]}"""
        val once = Fake(ArrayDeque(listOf(level, noKids, total, noExistingIds, whole)))
        val onceSession = open(once)
        onceSession.listLevel("", null, false)
        onceSession.select("INBOX")
        val mark = once.bodies.size
        val all = onceSession.sort(org.dlang.liveimap.settings.SortKey.Arrival, true)
        val sent = once.bodies.drop(mark)
        assertEquals(1, sent.size)
        assertTrue(!sent[0].contains("\"position\""))
        assertEquals(2, all.size)
    }

    @Test
    fun selectTotalZeroStillPagesOnTheQueryTotal() = runBlocking {
        val total0 = """{"methodResponses":[["Mailbox/get",{"list":[{"id":"m1","totalEmails":0}]},"0"]]}"""
        val one = """{"methodResponses":[["Email/query",{"ids":["a"],"total":2},"0"],["Email/get",{"list":[{"id":"a","receivedAt":"1970-01-01T00:03:20Z"}]},"1"]]}"""
        val two = """{"methodResponses":[["Email/query",{"ids":["b"],"total":2},"0"],["Email/get",{"list":[{"id":"b","receivedAt":"1970-01-01T00:01:40Z"}]},"1"]]}"""
        val paged = Fake(ArrayDeque(listOf(level, noKids, total0, noExistingIds, one, two)))
        val s = open(paged)
        s.listLevel("", null, false)
        s.select("INBOX")
        val from = paged.bodies.size
        s.sort(org.dlang.liveimap.settings.SortKey.Arrival, true)
        val calls = paged.bodies.drop(from)
        assertEquals(2, calls.size)
        for (call in calls) {
            assertTrue(call.contains("\"limit\":120"))
            assertTrue(call.contains("\"calculateTotal\":true"))
            assertTrue(!call.substringAfter("Email/get").contains("\"ids\":["))
        }
        assertTrue(!calls[0].contains("\"position\""))
        assertTrue(calls[1].contains("\"position\":1"))
        val whole = """{"methodResponses":[["Email/query",{"ids":["a","b"],"total":2},"0"],["Email/get",{"list":[{"id":"a","receivedAt":"1970-01-01T00:03:20Z"},{"id":"b","receivedAt":"1970-01-01T00:01:40Z"}]},"1"]]}"""
        val once = Fake(ArrayDeque(listOf(level, noKids, total0, noExistingIds, whole)))
        val onceSession = open(once)
        onceSession.listLevel("", null, false)
        onceSession.select("INBOX")
        val mark = once.bodies.size
        onceSession.sort(org.dlang.liveimap.settings.SortKey.Arrival, true)
        val sent = once.bodies.drop(mark)
        assertEquals(1, sent.size)
        assertTrue(!sent[0].contains("\"position\""))
        assertTrue(sent[0].contains("\"limit\":120"))
        assertTrue(sent[0].contains("\"calculateTotal\":true"))
    }

    private class Blobs(val status: Int = 200, val data: ByteArray = "RAW".toByteArray()) {
        val urls = mutableListOf<String>()
        val pins = mutableListOf<String>()
        fun getFor(pin: String, policy: TlsPolicy): (String, String) -> JmapBlobExchange = { url, _ ->
            pins += pin; urls += url
            object : JmapBlobExchange {
                override val status = this@Blobs.status
                override fun peerDer() = listOf(byteArrayOf(1))
                override fun bytes() = data
                override fun close() {}
            }
        }
    }

    private fun ready(replies: List<String>, blobs: Blobs = Blobs(), upload: (String, TlsPolicy) -> (String, ByteArray, String, String) -> JmapHttpExchange = { _, _ -> error("no upload") }): Pair<JmapMailSession, Fake> {
        val fake = Fake(ArrayDeque(listOf(level, noKids, total, existingIds, emails) + replies))
        val s = JmapMailSession({ _, _ -> JmapOffer.Mail(session) }, { "pw" }, fake::postFor, { _, _, _ -> "" }, { 42 }, blobs::getFor, upload)
        runBlocking {
            s.open(AccountSettings(username = "u", certPin = "imap", jmapCertPin = "jm"))
            s.listLevel("", null, false); s.select("INBOX")
            s.fetchIndex(IndexRequest("INBOX", IndexMode.ArrivalNewest, limit = 2))
        }
        return s to fake
    }

    private val structure = """{"methodResponses":[["Email/get",{"list":[{"id":"e1","bodyStructure":{"type":"multipart/mixed","subParts":[{"partId":"1","blobId":"b1","type":"text/plain","size":5},{"type":"multipart/alternative","subParts":[{"partId":"2","blobId":"b2","type":"text/html"}]},{"partId":"3","blobId":"b3","type":"image/png","disposition":"attachment","name":"p.png","size":9}]}}]},"0"]]}"""

    @Test
    fun structureNumbersSectionsLikeImapAndTextComesFromBodyValues() = runBlocking {
        val values = """{"methodResponses":[["Email/get",{"list":[{"id":"e1","bodyValues":{"1":{"value":"héllo","isTruncated":false}}}]},"0"]]}"""
        val (s, fake) = ready(listOf(structure, values))
        val tree = s.fetchStructure(e1Uid)
        assertEquals(listOf("1", "2", "3"), tree.children.map { it.section })
        assertEquals("2.1", tree.children[1].children[0].section)
        assertEquals("p.png", tree.children[2].filename)
        assertEquals("utf-8", tree.children[0].charset)
        assertArrayEquals("éllo".toByteArray(), s.peekPart(e1Uid, "1", 1, 100))
        assertTrue(fake.bodies.last().contains("\"fetchAllBodyValues\":true"))
        try { s.peekPart(e1Uid, "9", 0, 1); fail() } catch (_: MailFailure) {}
    }

    @Test
    fun peekPartSecondSliceUsesTheCache() = runBlocking {
        val values = """{"methodResponses":[["Email/get",{"list":[{"id":"e1","bodyValues":{"1":{"value":"0123456789abcdef","isTruncated":false}}}]},"0"]]}"""
        val (s, fake) = ready(listOf(structure, values))
        s.fetchStructure(e1Uid)
        val before = fake.bodies.size
        assertArrayEquals("0123".toByteArray(), s.peekPart(e1Uid, "1", 0, 4))
        assertArrayEquals("4567".toByteArray(), s.peekPart(e1Uid, "1", 4, 4))
        assertEquals(before + 1, fake.bodies.size)
        assertTrue(fake.bodies.last().contains("Email/get"))
    }

    @Test
    fun textPeekAsksForJmapMaxBodyAndReusesThatPrefix() = runBlocking {
        val full = "0123456789abcdef"
        val queued = ArrayDeque(listOf(level, noKids, total, existingIds, emails, structure))
        val bodies = mutableListOf<String>()
        val post: (String, TlsPolicy) -> (String, String, String) -> JmapHttpExchange = { _, _ ->
            { _, body, _ ->
                bodies += body
                val reply = if (body.contains("\"bodyValues\"")) {
                    val cap = Regex(""""maxBodyValueBytes":(\d+)""").find(body)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                    val n = cap.coerceIn(0, full.length)
                    val value = full.take(n)
                    val truncated = n < full.length
                    """{"methodResponses":[["Email/get",{"list":[{"id":"e1","bodyValues":{"1":{"value":"$value","isTruncated":$truncated}}}]},"0"]]}"""
                } else {
                    queued.removeFirst()
                }
                object : JmapHttpExchange {
                    override val status = 200
                    override fun peerDer() = listOf(byteArrayOf(1))
                    override fun header(name: String): String? = null
                    override fun body() = reply
                    override fun close() {}
                }
            }
        }
        val s = JmapMailSession({ _, _ -> JmapOffer.Mail(session) }, { "pw" }, post, { _, _, _ -> "" }, { 42 })
        assertEquals(OpenResult.Connected, s.open(AccountSettings(username = "u", certPin = "imap", jmapCertPin = "jm")))
        s.listLevel("", null, false)
        s.select("INBOX")
        s.fetchIndex(IndexRequest("INBOX", IndexMode.ArrivalNewest, limit = 2))
        s.fetchStructure(e1Uid)
        val before = bodies.size
        assertArrayEquals("0123".toByteArray(), s.peekPart(e1Uid, "1", 0, 4))
        assertArrayEquals("4567".toByteArray(), s.peekPart(e1Uid, "1", 4, 4))
        val calls = bodies.drop(before)
        assertEquals(1, calls.size)
        assertTrue(calls[0].contains("\"maxBodyValueBytes\":$JmapMaxBody"))
        assertTrue(calls[0].contains("Email/get"))
    }

    @Test
    fun shortTruncatedTextPrefixIsNotFetchedAgain() = runBlocking {
        val queued = ArrayDeque(listOf(level, noKids, total, existingIds, emails, structure))
        val bodies = mutableListOf<String>()
        val post: (String, TlsPolicy) -> (String, String, String) -> JmapHttpExchange = { _, _ ->
            { _, body, _ ->
                bodies += body
                val reply = if (body.contains("\"bodyValues\"")) {
                    """{"methodResponses":[["Email/get",{"list":[{"id":"e1","bodyValues":{"1":{"value":"abcd","isTruncated":true}}}]},"0"]]}"""
                } else {
                    queued.removeFirst()
                }
                object : JmapHttpExchange {
                    override val status = 200
                    override fun peerDer() = listOf(byteArrayOf(1))
                    override fun header(name: String): String? = null
                    override fun body() = reply
                    override fun close() {}
                }
            }
        }
        val s = JmapMailSession({ _, _ -> JmapOffer.Mail(session) }, { "pw" }, post, { _, _, _ -> "" }, { 42 })
        assertEquals(OpenResult.Connected, s.open(AccountSettings(username = "u", certPin = "imap", jmapCertPin = "jm")))
        s.listLevel("", null, false)
        s.select("INBOX")
        s.fetchIndex(IndexRequest("INBOX", IndexMode.ArrivalNewest, limit = 2))
        s.fetchStructure(e1Uid)
        val before = bodies.size
        assertArrayEquals("abcd".toByteArray(), s.peekPart(e1Uid, "1", 0, 4))
        assertArrayEquals(ByteArray(0), s.peekPart(e1Uid, "1", 4, 4))
        val calls = bodies.drop(before)
        assertEquals(1, calls.size)
        assertTrue(calls[0].contains("Email/get"))
        assertTrue(calls[0].contains("\"maxBodyValueBytes\":$JmapMaxBody"))
    }

    @Test
    fun peekPartPastAShortCacheAppendsTheBytesAtThatOffset() = runBlocking {
        val short = """{"methodResponses":[["Email/get",{"list":[{"id":"e1","bodyValues":{"1":{"value":"0123","isTruncated":true}}}]},"0"]]}"""
        val (s, fake) = ready(listOf(structure, short))
        s.fetchStructure(e1Uid)
        val before = fake.bodies.size
        assertArrayEquals("0123".toByteArray(), s.peekPart(e1Uid, "1", 0, 4))
        assertArrayEquals(ByteArray(0), s.peekPart(e1Uid, "1", 4, 4))
        val calls = fake.bodies.drop(before)
        assertEquals(1, calls.size)
        assertTrue(calls[0].contains("\"maxBodyValueBytes\":$JmapMaxBody"))
        val cached = fake.bodies.size
        assertArrayEquals(ByteArray(0), s.peekPart(e1Uid, "1", 8, 4))
        assertEquals(cached, fake.bodies.size)
        assertArrayEquals("0123".toByteArray(), s.peekPart(e1Uid, "1", 0, 4))
    }

    @Test
    fun peekPartTruncatedShortPrefixFetchesPastTheCache() = runBlocking {
        // A truncated character shorter than JmapMaxBody is kept, and a later peek does not ask again.
        val short = """{"methodResponses":[["Email/get",{"list":[{"id":"e1","bodyValues":{"1":{"value":"é","isTruncated":true}}}]},"0"]]}"""
        val (s, fake) = ready(listOf(structure, short))
        s.fetchStructure(e1Uid)
        val before = fake.bodies.size
        assertArrayEquals("é".toByteArray(), s.peekPart(e1Uid, "1", 0, 3))
        val cached = fake.bodies.size
        assertEquals(before + 1, cached)
        assertTrue(fake.bodies.last().contains("\"maxBodyValueBytes\":$JmapMaxBody"))
        assertArrayEquals("é".toByteArray(), s.peekPart(e1Uid, "1", 0, 2))
        assertEquals(cached, fake.bodies.size)
        assertArrayEquals(ByteArray(0), s.peekPart(e1Uid, "1", 2, 4))
        assertEquals(cached, fake.bodies.size)
    }

    @Test
    fun attachmentsAndRawMessagesDownloadOnTheApiHostWithTheJmapPin() = runBlocking {
        val blobId = """{"methodResponses":[["Email/get",{"list":[{"id":"e1","blobId":"raw 1"}]},"0"]]}"""
        val blobs = Blobs(data = "0123456789".toByteArray())
        val (s, _) = ready(listOf(structure, blobId), blobs)
        s.fetchStructure(e1Uid)
        assertArrayEquals("2345".toByteArray(), s.peekPart(e1Uid, "3", 2, 4))
        assertArrayEquals("0123456789".toByteArray(), s.fetchRfc822(e1Uid))
        assertEquals("https://mail.example/dl/A1/raw%201?type=message%2Frfc822", blobs.urls.last())
        assertEquals(listOf("jm", "jm"), blobs.pins)
    }

    @Test
    fun fetchStructureDropsThePreviousMessagesBytes() = runBlocking {
        val e2 = """{"methodResponses":[["Email/get",{"list":[{"id":"e2","bodyStructure":{"partId":"1","blobId":"b9","type":"text/plain","size":1}}]},"0"]]}"""
        val blobs = Blobs(data = "0123456789".toByteArray())
        val (s, _) = ready(listOf(structure, e2), blobs)
        s.fetchStructure(e1Uid)
        assertArrayEquals("0123".toByteArray(), s.peekPart(e1Uid, "3", 0, 4))
        assertEquals(1, blobs.urls.size)
        s.fetchStructure(e2Uid)
        assertArrayEquals("0123".toByteArray(), s.peekPart(e1Uid, "3", 0, 4))
        assertEquals(2, blobs.urls.size)
    }

    @Test
    fun downloadOffHostOrRedirectedFails() = runBlocking {
        val blobId = """{"methodResponses":[["Email/get",{"list":[{"id":"e1","blobId":"r"}]},"0"]]}"""
        val (s, _) = ready(listOf(blobId), Blobs(status = 302))
        try { s.fetchRfc822(e1Uid); fail() } catch (e: MailFailure) { assertTrue(e.text.contains("redirect")) }
        val off = session.copy(downloadUrl = "https://evil.example/{blobId}")
        try { jmapDownload(off, "r", "x/y", "a", "", { _, _ -> error("fetched") }, { _, _, _ -> "" }); fail() } catch (_: JmapFailure) {}
        val http = session.copy(downloadUrl = "http://mail.example/{blobId}")
        try { jmapDownload(http, "r", "x/y", "a", "", { _, _ -> error("fetched") }, { _, _, _ -> "" }); fail() } catch (_: JmapFailure) {}
    }

    @Test
    fun threadsGroupByThreadIdOldestFirst() = runBlocking {
        val reply = """{"methodResponses":[["Email/query",{"ids":["e2","e3","e1"]},"0"],["Email/get",{"list":[{"id":"e1","threadId":"T"},{"id":"e2","threadId":"T"},{"id":"e3","threadId":"U"}]},"1"]]}"""
        val (s, _) = ready(listOf(reply))
        val tree = s.thread(org.dlang.liveimap.settings.SortKey.ThreadReferences)
        assertEquals(listOf(e2Uid, e2Uid + 1), tree.children.map { it.uid })
        assertEquals(listOf(e1Uid), tree.children[0].children.map { it.uid })
    }

    @Test
    fun saveUploadsThenImportsWithKeywords() = runBlocking {
        val imported = """{"methodResponses":[["Email/import",{"created":{"k1":{"id":"e9"}}},"0"]]}"""
        val uploads = mutableListOf<String>()
        val upload: (String, TlsPolicy) -> (String, ByteArray, String, String) -> JmapHttpExchange = { pin, _ ->
            { url, _, _, _ ->
                uploads += "$pin $url"
                object : JmapHttpExchange {
                    override val status = 201
                    override fun peerDer() = listOf(byteArrayOf(1))
                    override fun header(name: String): String? = null
                    override fun body() = """{"blobId":"B9"}"""
                    override fun close() {}
                }
            }
        }
        val (s, fake) = ready(listOf(imported), upload = upload)
        assertEquals(1L, s.appendReturningUid("Trash", "x".toByteArray(), setOf("\\Seen")))
        assertEquals(listOf("jm https://mail.example/up/A1"), uploads)
        assertTrue(fake.bodies.last().contains("\"blobId\":\"B9\",\"mailboxIds\":{\"m2\":true},\"keywords\":{\"${'$'}seen\":true}"))
        try { s.append("INBOX", ByteArray(0)); fail() } catch (_: MailFailure) {}
        try { s.append("INBOX", "x".toByteArray(), setOf("\\Deleted")); fail() } catch (_: MailFailure) {}
    }

    @Test
    fun saveRefusedByServerFails() = runBlocking {
        val refused = """{"methodResponses":[["Email/import",{"notCreated":{"k1":{"type":"overQuota"}}},"0"]]}"""
        val upload: (String, TlsPolicy) -> (String, ByteArray, String, String) -> JmapHttpExchange = { _, _ -> { _, _, _, _ ->
            object : JmapHttpExchange {
                override val status = 201
                override fun peerDer() = listOf(byteArrayOf(1))
                override fun header(name: String): String? = null
                override fun body() = """{"blobId":"B"}"""
                override fun close() {}
            }
        } }
        val (s, _) = ready(listOf(refused), upload = upload)
        try { s.append("INBOX", "x".toByteArray()); fail() } catch (e: MailFailure) { assertTrue(e.text.contains("overQuota")) }
    }

    @Test
    fun sendOverJmapNeedsTheOptionAndASubmissionCapability() = runBlocking {
        // Without "send over JMAP" the session does not send; the account's SMTP server does.
        val plain = open(Fake(ArrayDeque()))
        try { plain.smtpSend("x".toByteArray(), listOf("a@b")); fail() } catch (e: MailFailure) { assertTrue(e.text.contains("SMTP")) }
        // With it, a server that does not offer urn:ietf:params:jmap:submission fails before any upload.
        val fake = Fake(ArrayDeque())
        val jm = open(fake, AccountSettings(username = "u", email = "u@x", jmapCertPin = "jm", emailSubmission = true))
        try { jm.smtpSend("x".toByteArray(), listOf("a@b")); fail() } catch (e: MailFailure) { assertTrue(e.text, e.text.contains("submission")) }
        assertTrue(fake.bodies.isEmpty())
    }

    private class Stream(val responses: ArrayDeque<Pair<Int, String>>, val closeFrees: Boolean = true) {
        val urls = mutableListOf<String>()
        val blocked = java.util.concurrent.CountDownLatch(1)
        fun streamFor(pin: String, policy: TlsPolicy): (String, String) -> JmapHttpExchange = { url, _ ->
            urls += "$pin $url"
            val next = synchronized(responses) { responses.removeFirstOrNull() }
            val released = java.util.concurrent.CountDownLatch(1)
            object : JmapHttpExchange {
                override val status = next?.first ?: 200
                override fun peerDer() = listOf(byteArrayOf(1))
                override fun header(name: String): String? = null
                override fun body(): String {
                    if (next != null) return next.second
                    blocked.countDown()
                    released.await(if (closeFrees) 5L else 20L, java.util.concurrent.TimeUnit.SECONDS) // a long-poll that only close() ends
                    throw IOException("closed")
                }
                override fun close() { if (closeFrees) released.countDown() }
            }
        }
    }

    private fun watching(replies: List<String>, stream: Stream, events: String = "https://mail.example/events"): JmapMailSession {
        val fake = Fake(ArrayDeque(listOf(level, noKids, total, existingIds, emails) + replies))
        val s = JmapMailSession({ _, _ -> JmapOffer.Mail(session.copy(eventSourceUrl = events)) }, { "pw" }, fake::postFor, { _, _, _ -> "" }, { 42 },
            Blobs()::getFor, { _, _ -> error("no upload") }, stream::streamFor)
        runBlocking {
            s.open(AccountSettings(username = "u", jmapCertPin = "jm"))
            s.listLevel("", null, false); s.select("INBOX")
            s.fetchIndex(IndexRequest("INBOX", IndexMode.ArrivalNewest, limit = 2))
        }
        return s
    }

    private val state0 = """{"methodResponses":[["Email/get",{"state":"S0","list":[]},"0"]]}"""
    private fun totalOf(n: Int) = """{"methodResponses":[["Mailbox/get",{"list":[{"id":"m1","totalEmails":$n}]},"0"]]}"""
    private val pushS1 = "event: state\ndata: {\"@type\":\"StateChange\",\"changed\":{\"A1\":{\"Email\":\"S1\"}}}\n\n"

    @Test
    fun pushTurnsAStateChangeIntoFlagsAndExists() = runBlocking {
        val changes = """{"methodResponses":[["Email/changes",{"oldState":"S0","newState":"S1","hasMoreChanges":false,"created":["e7"],"updated":["e1"],"destroyed":[]},"0"]]}"""
        val got = """{"methodResponses":[["Email/get",{"list":[{"id":"e1","keywords":{"${'$'}flagged":true},"mailboxIds":{"m1":true}}]},"0"]]}"""
        val stream = Stream(ArrayDeque(listOf(200 to ": ping\n\n", 200 to pushS1)))
        val s = watching(listOf(state0, totalOf(2), changes, got, totalOf(3)), stream)
        val seen = java.util.Collections.synchronizedList(mutableListOf<org.dlang.liveimap.session.MailboxChange>())
        s.watch("INBOX") { seen += it }
        assertTrue(stream.blocked.await(5, java.util.concurrent.TimeUnit.SECONDS))
        assertEquals(listOf(org.dlang.liveimap.session.MailboxChange.Flags(e1Uid, setOf("\\Flagged")), org.dlang.liveimap.session.MailboxChange.Exists(3)), seen.toList())
        assertTrue(stream.urls.all { it.startsWith("jm https://mail.example/events?types=Email") })
        s.stopWatch() // closes the blocked long-poll and joins
        assertEquals(seen.toList().toString(), 2, seen.size)
    }

    @Test
    fun stopWatchDoesNotWaitOnAReadCloseCannotFree() = runBlocking {
        // Stalwart lab: close() did not free the blocked eventsource read, and the suite hung in stopWatch.
        val stream = Stream(ArrayDeque(), closeFrees = false)
        val s = watching(listOf(state0, totalOf(2)), stream)
        s.watch("INBOX") { }
        assertTrue(stream.blocked.await(5, java.util.concurrent.TimeUnit.SECONDS))
        val start = System.nanoTime()
        s.stopWatch()
        val ms = (System.nanoTime() - start) / 1_000_000
        assertTrue("stopWatch took $ms ms", ms < JmapMailSession.STOP_WATCH_WAIT_MS + 2_000)
    }

    @Test
    fun accountWithoutSubmissionIsNotOffered() {
        val text = """{"capabilities":{"urn:ietf:params:jmap:core":{},"urn:ietf:params:jmap:mail":{},"urn:ietf:params:jmap:submission":{}},
            "accounts":{"A1":{"accountCapabilities":{"urn:ietf:params:jmap:mail":{}}}},"primaryAccounts":{"urn:ietf:params:jmap:mail":"A1"},
            "username":"u","apiUrl":"https://x/api","downloadUrl":"https://x/d","uploadUrl":"https://x/u","eventSourceUrl":"https://x/e","state":"s"}"""
        assertEquals(false, parseJmapSession(text).offersSubmission())
        val withIt = text.replace(""""accountCapabilities":{"urn:ietf:params:jmap:mail":{}}""", """"accountCapabilities":{"urn:ietf:params:jmap:mail":{},"urn:ietf:params:jmap:submission":{}}""")
        assertEquals(true, parseJmapSession(withIt).offersSubmission())
        // Not listed per account: the session-level capability decides, as before.
        assertEquals(true, parseJmapSession(text.replace(""""accountCapabilities":{"urn:ietf:params:jmap:mail":{}}""", "")).offersSubmission())
    }

    @Test
    fun pushFailureEndsTheWatchWithWatchLost() = runBlocking {
        val stream = Stream(ArrayDeque(listOf(302 to "")))
        val s = watching(listOf(state0, totalOf(2)), stream)
        val lost = java.util.concurrent.CountDownLatch(1)
        s.watch("INBOX") { if (it == org.dlang.liveimap.session.MailboxChange.WatchLost) lost.countDown() }
        assertTrue(lost.await(5, java.util.concurrent.TimeUnit.SECONDS))
        s.stopWatch()
    }

    @Test
    fun pushOffHostOrPlainHttpIsRefusedBeforeConnecting() = runBlocking {
        for (url in listOf("https://evil.example/events", "http://mail.example/events")) {
            val stream = Stream(ArrayDeque())
            val s = watching(listOf(state0, totalOf(2)), stream, url)
            try { s.watch("INBOX") {}; fail(url) } catch (_: MailFailure) {}
            assertTrue(stream.urls.isEmpty())
        }
    }

    @Test
    fun eventSourceTemplateExpandsLevelOneAndStaysOnHost() {
        assertEquals("https://mail.example/ev?types=Email&closeafter=state&ping=120",
            jmapEventSourceUrl("https://mail.example/ev?types={types}&closeafter={closeafter}&ping={ping}"))
        assertEquals("https://mail.example/ev/Email/state?x=", jmapEventSourceUrl("https://mail.example/ev/{types}/{closeafter}?x={other}"))
        for (bad in listOf("https://mail.example/ev?t={+types}", "https://mail.example/ev?t={types", "https://mail.example/ev}",
                "https://{types}.example/ev", "http://mail.example/ev?t={types}")) {
            try { jmapEventSourceUrl(bad); fail(bad) } catch (_: JmapFailure) {}
        }
        assertEquals("https://mail.example/ev?types=Email&closeafter=state&ping=120", jmapEventSourceUrl("https://mail.example/ev"))
    }

    private fun pinSession(confirm: Boolean?, saved: MutableList<String>, seenPins: MutableList<String>, offerTwice: Boolean = false): JmapMailSession {
        var calls = 0
        val s = JmapMailSession(
            discover = { account, _ ->
                seenPins += account.jmapCertPin
                calls++
                if (calls == 1 || offerTwice) throw JmapPinOffer("certificate changed", "mail.example", listOf(byteArrayOf(1)))
                JmapOffer.Mail(session)
            },
            passwordOf = { "pw" },
            savePin = { saved += it },
            describe = { arrayOf("CN=s", "CN=i", "a", "b", "FP") },
        )
        if (confirm != null) s.setCertConfirmer { prompt -> assertEquals("FP", prompt.fingerprint); assertEquals("certificate changed", prompt.reason); confirm }
        return s
    }

    @Test
    fun changedCertificateIsOfferedAndPinnedAsTheJmapPin() = runBlocking {
        val saved = mutableListOf<String>(); val pins = mutableListOf<String>()
        val s = pinSession(true, saved, pins)
        assertEquals(OpenResult.Connected, s.open(AccountSettings(username = "u", certPin = "imap", jmapCertPin = "old")))
        assertEquals(listOf("FP"), saved)
        assertEquals(listOf("old", "FP"), pins)
    }

    @Test
    fun declinedMissingOrRepeatedOfferFailsWithoutSaving() = runBlocking {
        for (case in listOf<Pair<Boolean?, Boolean>>(false to false, null to false, true to true)) {
            val saved = mutableListOf<String>(); val pins = mutableListOf<String>()
            val s = pinSession(case.first, saved, pins, offerTwice = case.second)
            val r = s.open(AccountSettings(username = "u"))
            assertEquals(case.toString(), OpenResult.Failed("certificate changed"), r)
            if (case.second) assertEquals(listOf("FP"), saved) else assertTrue(saved.isEmpty())
        }
    }

    @Test
    fun selectOnAFreshSessionListsDownToANestedFolder() = runBlocking {
        val root = """{"methodResponses":[["Mailbox/get",{"list":[{"id":"m1","name":"INBOX","role":"inbox","totalEmails":0,"unreadEmails":0},{"id":"w","name":"Work","totalEmails":0,"unreadEmails":0}]},"0"]]}"""
        val kidsOfW = """{"methodResponses":[["Mailbox/get",{"list":[{"parentId":"w"}]},"0"]]}"""
        val work = """{"methodResponses":[["Mailbox/get",{"list":[{"id":"w26","name":"2026","parentId":"w","totalEmails":4,"unreadEmails":0}]},"0"]]}"""
        val tot = """{"methodResponses":[["Mailbox/get",{"list":[{"id":"w26","totalEmails":4}]},"0"]]}"""
        val fake = Fake(ArrayDeque(listOf(root, kidsOfW, work, noKids, tot, noExistingIds)))
        val s = open(fake)
        assertEquals(4, s.select("Work/2026").exists)
        assertTrue(fake.bodies.last().contains("\"w26\""))
    }

    @Test
    fun acceptInvalidCertIsPassedToFolderUploadSendDownloadAndPush() = runBlocking {
        val seen = mutableListOf<TlsPolicy>()
        val replies = ArrayDeque(listOf(
            level, noKids, total, existingIds, emails,
            """{"methodResponses":[["Email/get",{"list":[{"id":"e1","blobId":"raw1"}]},"0"]]}""",
            """{"methodResponses":[["Email/import",{"created":{"k1":{"id":"e9"}}},"0"]]}""",
            """{"methodResponses":[]}""",
            state0, totalOf(2),
        ))
        fun exchange(reply: String, status: Int = 200) = object : JmapHttpExchange {
            override val status = status
            override fun peerDer() = listOf(byteArrayOf(1))
            override fun header(name: String): String? = null
            override fun body() = reply
            override fun close() {}
        }
        val streamed = java.util.concurrent.CountDownLatch(1)
        var posts = 0
        var gets = 0
        var uploads = 0
        var streams = 0
        val mail = session.copy(capabilityIds = setOf(JMAP_MAIL, JMAP_SUBMISSION))
        val s = JmapMailSession(
            discover = { _, _ -> JmapOffer.Mail(mail) },
            passwordOf = { "pw" },
            postFor = { _, policy ->
                posts++
                seen += policy
                { _, _, _ -> exchange(replies.removeFirst()) }
            },
            trust = { _, _, _ -> "" },
            epochClock = { 42 },
            getFor = { _, policy ->
                gets++
                seen += policy
                { _, _ -> object : JmapBlobExchange {
                    override val status = 200
                    override fun peerDer() = listOf(byteArrayOf(1))
                    override fun bytes() = "RAW".toByteArray()
                    override fun close() {}
                } }
            },
            uploadFor = { _, policy ->
                uploads++
                seen += policy
                { _, _, _, _ -> exchange("""{"blobId":"B9"}""", 201) }
            },
            streamFor = { _, policy ->
                streams++
                seen += policy
                streamed.countDown();
                { _, _ -> exchange("") }
            },
        )
        val account = AccountSettings(username = "u", acceptInvalidCert = true, email = "u@x", emailSubmission = true, jmapCertPin = "jm")
        assertEquals(OpenResult.Connected, s.open(account))
        s.listLevel("", null, false)
        s.select("INBOX")
        s.fetchIndex(IndexRequest("INBOX", IndexMode.ArrivalNewest, limit = 2))
        assertArrayEquals("RAW".toByteArray(), s.fetchRfc822(e1Uid))
        assertTrue(s.appendReturningUid("INBOX", "x".toByteArray(), emptySet()) > 0L)
        try { s.smtpSend("From: u@x\r\n\r\nhi\r\n".toByteArray(), listOf("a@b")); fail() } catch (_: MailFailure) {}
        s.watch("INBOX") { }
        assertTrue(streamed.await(5, java.util.concurrent.TimeUnit.SECONDS))
        s.stopWatch()
        assertTrue(posts > 0 && gets > 0 && uploads > 0 && streams > 0)
        assertTrue(seen.all { it.acceptInvalidCert })
    }
}
