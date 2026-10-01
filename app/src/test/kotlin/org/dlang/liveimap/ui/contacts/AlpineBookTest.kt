package org.dlang.liveimap.ui.contacts

import org.dlang.liveimap.session.FolderEntry
import org.dlang.liveimap.session.IndexMode
import org.dlang.liveimap.session.IndexRequest
import org.dlang.liveimap.session.IndexRow
import org.dlang.liveimap.session.MailFailure
import org.dlang.liveimap.session.MailSession
import org.dlang.liveimap.session.MailboxChange
import org.dlang.liveimap.session.MimePart
import org.dlang.liveimap.session.Namespace
import org.dlang.liveimap.session.OpenResult
import org.dlang.liveimap.session.SelectResult
import org.dlang.liveimap.session.SelectedAddress
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

class AlpineBookTest {
    @Test
    fun headerlessBodyIsRejected() {
        val session = FakeBookSession()
        session.rows = listOf(row(1), row(3))
        session.bytes[1] = ascii("X-Pine-Addrbook: 1\r\n\r\n")
        session.bytes[3] = ascii("nick\tName\tnick@example.com\n")
        val loaded = runImmediate { loadAlpineBook(session, "abook") }
        assertEquals("This mailbox is not an Alpine address book", loaded.notice)
        assertTrue(loaded.entries.isEmpty())
        assertEquals(listOf(1L, 3L), session.rfc822Uids)
        assertEquals(listOf("abook"), session.selected)
        assertEquals(0, session.appendCount)
        assertEquals(0, session.storeCount)
        assertEquals(0, session.smtpCount)
    }

    @Test
    fun normalMailFolderIsNotParsed() {
        val session = FakeBookSession()
        session.rows = listOf(row(4), row(8))
        session.bytes[4] = ascii("From: Ann <ann@example.com>\r\nSubject: Hello\r\n\r\nHello\r\n")
        session.bytes[8] = ascii(
            "From: Ann <ann@example.com>\r\nSubject: Book\r\n\r\nnick\tName\tnick@example.com\r\n",
        )
        val loaded = runImmediate { loadAlpineBook(session, "INBOX") }
        assertEquals("This mailbox is not an Alpine address book", loaded.notice)
        assertTrue(loaded.entries.isEmpty())
        assertEquals(listOf(4L, 8L), session.rfc822Uids)
    }

    @Test
    fun plaintextIsNotPartOfTheEmail() {
        val entry = parseAlpineBook(
            "ada\tAda Lovelace\tada@example.com\tSent\tkeep [plaintext]\n",
        ).single()
        assertEquals("ada", entry.nickname)
        assertEquals("Ada Lovelace", entry.fullname)
        assertEquals("ada@example.com", entry.address)
        assertEquals("Sent", entry.fcc)
        assertEquals("keep [plaintext]", entry.comments)
        val picked = pickedAddresses(entry).single()
        assertEquals(SelectedAddress("Ada Lovelace", "ada@example.com"), picked)
        assertFalse(picked.email.contains("[plaintext]"))
        assertTrue(entry.comments.contains("[plaintext]"))
        val glued = parseAlpineBook("ada\tAda\tada@example.com[plaintext]\t\t\n").single()
        assertEquals("ada@example.com", glued.address)
        assertEquals("[plaintext]", glued.comments)
        assertEquals("ada@example.com", pickedAddresses(glued).single().email)
        assertFalse(pickedAddresses(glued).single().email.contains("[plaintext]"))
    }

    @Test
    fun emptyNicknameStillParses() {
        val entry = parseAlpineBook("\tAda Lovelace\tada@example.com\n").single()
        assertEquals("", entry.nickname)
        assertEquals("Ada Lovelace", entry.fullname)
        assertEquals("ada@example.com", entry.address)
        assertEquals("", entry.fcc)
        assertEquals("", entry.comments)
        assertEquals(listOf(SelectedAddress("Ada Lovelace", "ada@example.com")), pickedAddresses(entry))
    }

    @Test
    fun spaceContinuationStaysOneEntry() {
        val entry = parseAlpineBook(
            "ada\tAda Lovelace\tada@example.com\t\tnote\n continues\n",
        ).single()
        assertEquals("note continues", entry.comments)
        assertEquals("ada@example.com", entry.address)
    }

    @Test
    fun distributionListSkipsEmptyAddresses() {
        val entry = parseAlpineBook(
            "team\tTeam\t(ada@example.com,\n \"Bo\" <bo@example.com>, )\tSent\t\n",
        ).single()
        assertEquals("(ada@example.com, \"Bo\" <bo@example.com>, )", entry.address)
        assertEquals("Sent", entry.fcc)
        assertEquals(
            listOf(
                SelectedAddress("Team", "ada@example.com"),
                SelectedAddress("Team", "bo@example.com"),
            ),
            pickedAddresses(entry),
        )
        val blank = parseAlpineBook("nick\tName\t\t\tcomment\n").single()
        assertTrue(pickedAddresses(blank).isEmpty())
    }

    @Test
    fun lastMessageBodyIsTheBook() {
        val session = FakeBookSession()
        session.rows = listOf(row(1), row(2), row(5))
        session.bytes[1] = ascii("X-Pine-Addrbook: 1\r\n\r\nfirst\tFirst\tfirst@example.com\r\n")
        session.bytes[2] = ascii("Subject: old\r\n\r\nold\tOld\told@example.com\r\n")
        session.bytes[5] = ascii(
            "Subject: data\r\n\r\n\tAda Lovelace\tada@example.com\t\tkeep [plaintext]\r\n",
        )
        val loaded = runImmediate { loadAlpineBook(session, "abook") }
        assertNull(loaded.notice)
        val entry = loaded.entries.single()
        assertEquals("", entry.nickname)
        assertEquals("Ada Lovelace", entry.fullname)
        assertEquals("ada@example.com", entry.address)
        assertTrue(entry.comments.contains("[plaintext]"))
        assertFalse(pickedAddresses(entry).single().email.contains("[plaintext]"))
        assertEquals(listOf(1L, 5L), session.rfc822Uids)
        assertEquals(IndexMode.ArrivalOldest, session.fetchRequests[0].mode)
        assertEquals(1, session.fetchRequests[0].limit)
        assertEquals(0, session.fetchRequests[0].prefetch)
        assertEquals("abook", session.fetchRequests[0].mailbox)
        assertEquals(IndexMode.ArrivalNewest, session.fetchRequests[1].mode)
        assertEquals(0, session.appendCount)
        assertEquals(0, session.storeCount)
        assertEquals(0, session.smtpCount)
    }

    @Test
    fun emptyMailboxDoesNotSelect() {
        val session = FakeBookSession()
        val loaded = runImmediate { loadAlpineBook(session, "") }
        assertEquals("Address book mailbox is not set", loaded.notice)
        assertTrue(loaded.entries.isEmpty())
        assertTrue(session.selected.isEmpty())
        assertTrue(session.fetchRequests.isEmpty())
        assertTrue(session.rfc822Uids.isEmpty())
    }

    @Test
    fun mailFailureUsesText() {
        val session = FakeBookSession()
        session.failure = MailFailure("select broke")
        val error = try {
            runImmediate { loadAlpineBook(session, "abook") }
            null
        } catch (error: MailFailure) {
            error
        }
        assertEquals("select broke", error?.text)
    }
}

private fun ascii(text: String): ByteArray = text.toByteArray(Charsets.US_ASCII)

private fun row(uid: Long) = IndexRow(
    uid = uid,
    sequence = uid.toInt(),
    flags = emptySet(),
    internalDateEpoch = uid,
    size = 10,
    from = "",
    subject = "",
    envelopeDate = "",
    preview = null,
)

private class FakeBookSession : MailSession {
    override var capabilities: Set<String> = emptySet()
    val selected = mutableListOf<String>()
    val fetchRequests = mutableListOf<IndexRequest>()
    val rfc822Uids = mutableListOf<Long>()
    var rows: List<IndexRow> = emptyList()
    val bytes = HashMap<Long, ByteArray>()
    var failure: MailFailure? = null
    var appendCount: Int = 0
    var storeCount: Int = 0
    var smtpCount: Int = 0

    override suspend fun open(account: AccountSettings): OpenResult = unused()

    override suspend fun namespaces(): List<Namespace> = unused()

    override suspend fun listLevel(prefix: String, parentMailbox: String?): List<FolderEntry> = unused()

    override suspend fun select(mailbox: String): SelectResult {
        throwIfArmed()
        selected += mailbox
        return SelectResult(1, rows.size.toLong() + 1L, rows.size)
    }

    override suspend fun unselect() = Unit

    override suspend fun fetchIndex(request: IndexRequest): List<IndexRow> {
        fetchRequests += request
        throwIfArmed()
        if (rows.isEmpty()) return emptyList()
        return when (request.mode) {
            IndexMode.ArrivalOldest -> listOf(rows.first())
            IndexMode.ArrivalNewest -> listOf(rows.last())
            IndexMode.ByUid -> emptyList()
        }
    }

    override suspend fun fetchStructure(uid: Long): MimePart = unused()

    override suspend fun peekPart(uid: Long, section: String, offset: Int, length: Int): ByteArray = unused()

    override suspend fun fetchRfc822(uid: Long): ByteArray {
        throwIfArmed()
        rfc822Uids += uid
        return bytes[uid] ?: ByteArray(0)
    }

    override suspend fun storeFlags(uids: List<Long>, add: Set<String>, remove: Set<String>) {
        storeCount += 1
    }

    override suspend fun uidExpungeDeleted() = Unit

    override suspend fun copyThenDelete(uids: List<Long>, targetMailbox: String) = Unit

    override suspend fun searchText(query: String): List<Long> = unused()

    override suspend fun sort(key: SortKey, newestFirst: Boolean): List<Long> = unused()

    override suspend fun thread(key: SortKey): ThreadNode = unused()

    override suspend fun watch(mailbox: String, onChange: (MailboxChange) -> Unit) = Unit

    override suspend fun stopWatch() = Unit

    override suspend fun append(mailbox: String, rfc822: ByteArray) {
        appendCount += 1
    }

    override suspend fun smtpSend(rfc822: ByteArray, recipients: List<String>) {
        smtpCount += 1
    }

    override fun close() = Unit

    private fun throwIfArmed() {
        val pending = failure
        if (pending != null) {
            failure = null
            throw pending
        }
    }

    private fun unused(): Nothing = throw MailFailure("not used")
}

private fun <T> runImmediate(block: suspend () -> T): T {
    var result: Result<T>? = null
    block.startCoroutine(Continuation(EmptyCoroutineContext) { result = it })
    return result!!.getOrThrow()
}
