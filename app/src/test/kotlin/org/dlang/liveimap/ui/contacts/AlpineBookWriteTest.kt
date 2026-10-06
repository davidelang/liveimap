package org.dlang.liveimap.ui.contacts

import kotlinx.coroutines.runBlocking
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
import org.dlang.liveimap.settings.decodeAccountSettings
import org.dlang.liveimap.settings.encode
import org.dlang.liveimap.settings.previewPinerc
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlpineBookWriteTest {
    @Test
    fun formatRoundTripsEmptyNicknamePlaintextAndList() {
        val entries = listOf(
            AlpineEntry("", "Ada Lovelace", "ada@example.com", "", ""),
            AlpineEntry("ada", "Ada", "ada@example.com", "Sent", "keep [plaintext]"),
            AlpineEntry("team", "Team", "(ada@example.com, bo@example.com)", "", "list note"),
        )
        val parsed = parseAlpineBook(formatAlpineBook(entries))
        assertEquals(entries, parsed)
        assertEquals("", parsed[0].nickname)
        assertTrue(parsed[1].comments.contains("[plaintext]"))
        assertFalse(parsed[1].address.contains("[plaintext]"))
        assertEquals("(ada@example.com, bo@example.com)", parsed[2].address)
    }

    @Test
    fun tabAndNewlineInAFieldBecomeSpaces() {
        val entry = parseAlpineBook(
            formatAlpineBook(listOf(AlpineEntry("a\tb", "Line\nName", "ada@example.com", "", "c\r\nd"))),
        ).single()
        assertEquals("a b", entry.nickname)
        assertEquals("Line Name", entry.fullname)
        assertEquals("c  d", entry.comments)
    }

    @Test
    fun historyWindowDropsOnlyOlderRevisions() {
        val uids = listOf(1L, 2L, 3L, 4L, 5L, 6L)
        assertEquals(listOf(2L), revisionsToExpunge(uids, headerUid = 1L, history = 3, neverTrim = false, uidPlus = true))
        assertEquals(emptyList<Long>(), revisionsToExpunge(uids, 1L, 3, neverTrim = true, uidPlus = true))
        assertEquals(emptyList<Long>(), revisionsToExpunge(uids, 1L, 3, neverTrim = false, uidPlus = false))
        val samples = listOf(
            revisionsToExpunge(uids, 1L, 3, false, true),
            revisionsToExpunge(uids, 1L, 0, false, true),
            revisionsToExpunge(listOf(5L, 1L, 2L), 1L, 0, false, true),
            revisionsToExpunge(uids, 1L, 3, true, true),
            revisionsToExpunge(uids, 1L, 3, false, false),
        )
        for (sample in samples) assertFalse(sample.contains(1L))
    }

    @Test
    fun missingHistoryKeysStayAtDefaults() {
        val text = AccountSettings().encode()
        assertFalse(text.contains("addressBookHistory="))
        assertFalse(text.contains("addressBookNeverTrim="))
        val decoded = decodeAccountSettings(text)
        assertEquals(3, decoded.addressBookHistory)
        assertFalse(decoded.addressBookNeverTrim)
        val stripped = AccountSettings(addressBookHistory = 5, addressBookNeverTrim = true).encode()
            .lineSequence()
            .filterNot { it.startsWith("addressBookHistory=") || it.startsWith("addressBookNeverTrim=") }
            .joinToString("\n")
        assertEquals(3, decodeAccountSettings(stripped).addressBookHistory)
        assertFalse(decodeAccountSettings(stripped).addressBookNeverTrim)
        val round = AccountSettings(addressBookHistory = 5, addressBookNeverTrim = true)
        assertEquals(round, decodeAccountSettings(round.encode()))
    }

    @Test
    fun pinercHistoryIsDigitsOnlyAndDoesNotTrim() {
        val ok = previewPinerc("remote-abook-history=4\n", AccountSettings())
        assertEquals(4, ok.next.addressBookHistory)
        assertFalse(ok.next.addressBookNeverTrim)
        assertFalse(ok.skipped.contains("Address book history is not a number."))
        val bad = previewPinerc("remote-abook-history=nope\n", AccountSettings())
        assertEquals(3, bad.next.addressBookHistory)
        assertFalse(bad.next.addressBookNeverTrim)
        assertTrue(bad.skipped.contains("Address book history is not a number."))
    }

    @Test
    fun pineWriteAppendsAndExpungesOnlyTheHistoryUids() = runBlocking {
        val session = WriteBookSession(uidPlus = true)
        val headerBefore = session.bytes.getValue(1L).copyOf()
        val entries = listOf(AlpineEntry("ada", "Ada", "ada@example.com", "Sent", "keep [plaintext]"))
        val wrote = writePineBook(session, "abook", expectedLastUid = 6L, entries, history = 3, neverTrim = false)
        val expected = revisionsToExpunge((1L..7L).toList(), 1L, 3, neverTrim = false, uidPlus = true)
        assertEquals(listOf(2L, 3L), expected)
        assertEquals(PineWriteResult.Wrote(expected), wrote)
        assertEquals(1, session.appendCount)
        assertEquals(listOf(expected to setOf("\\Deleted")), session.stored)
        assertEquals(expected, session.expunged)
        assertEquals(0, session.deletedExpunges)
        assertTrue(session.bytes.getValue(1L).contentEquals(headerBefore))
        val appended = session.appended.decodeToString()
        assertTrue(appended.startsWith("X-Pine-Addrbook: 1\r\nSubject: book\r\n\r\n"))
        assertTrue(appended.contains("ada\tAda\tada@example.com\tSent\tkeep [plaintext]\n"))
        assertFalse(session.expunged.orEmpty().contains(1L))
    }

    @Test
    fun neverTrimAndMissingUidPlusAppendWithoutExpunge() = runBlocking {
        val never = WriteBookSession(uidPlus = true)
        val kept = writePineBook(
            never,
            "abook",
            6L,
            listOf(AlpineEntry("ada", "Ada", "ada@example.com", "", "")),
            history = 3,
            neverTrim = true,
        )
        assertEquals(PineWriteResult.Wrote(emptyList()), kept)
        assertEquals(1, never.appendCount)
        assertTrue(never.stored.isEmpty())
        assertEquals(null, never.expunged)
        assertEquals(0, never.deletedExpunges)

        val plain = WriteBookSession(uidPlus = false)
        val untrimmed = writePineBook(
            plain,
            "abook",
            6L,
            listOf(AlpineEntry("ada", "Ada", "ada@example.com", "", "")),
            history = 3,
            neverTrim = false,
        )
        assertEquals(PineWriteResult.Wrote(emptyList()), untrimmed)
        assertEquals(1, plain.appendCount)
        assertTrue(plain.stored.isEmpty())
        assertEquals(null, plain.expunged)
        assertEquals(0, plain.deletedExpunges)
    }

    @Test
    fun changedLastUidDoesNotAppend() = runBlocking {
        val session = WriteBookSession(uidPlus = true)
        val result = writePineBook(
            session,
            "abook",
            expectedLastUid = 5L,
            entries = listOf(AlpineEntry("ada", "Ada", "ada@example.com", "", "")),
            history = 3,
            neverTrim = false,
        )
        assertTrue(result is PineWriteResult.Stale)
        assertEquals(6L, (result as PineWriteResult.Stale).state.lastUid)
        assertEquals(0, session.appendCount)
        assertTrue(session.stored.isEmpty())
        assertEquals(null, session.expunged)
        assertEquals(0, session.deletedExpunges)
    }
}

private class WriteBookSession(uidPlus: Boolean) : MailSession {
    override var capabilities: Set<String> = if (uidPlus) setOf("UIDPLUS") else emptySet()
    val uids = (1L..6L).toMutableList()
    val bytes = HashMap<Long, ByteArray>()
    var appendCount = 0
    var appended = ByteArray(0)
    val stored = ArrayList<Pair<List<Long>, Set<String>>>()
    var expunged: List<Long>? = null
    var deletedExpunges = 0

    init {
        for (uid in uids) {
            bytes[uid] = "X-Pine-Addrbook: 1\r\nSubject: book\r\n\r\nold\tName\told@example.com\r\n".toByteArray()
        }
    }

    override suspend fun open(account: AccountSettings): OpenResult = unused()

    override suspend fun namespaces(): List<Namespace> = unused()

    override suspend fun listLevel(prefix: String, parentMailbox: String?, unreadCounts: Boolean): List<FolderEntry> =
        unused()

    override suspend fun select(mailbox: String): SelectResult =
        SelectResult(1L, uids.size.toLong() + 1L, uids.size)

    override suspend fun unselect() = Unit

    override suspend fun fetchIndex(request: IndexRequest): List<IndexRow> =
        uids.map { uid ->
            IndexRow(
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
        }

    override suspend fun fetchStructure(uid: Long): MimePart = unused()

    override suspend fun peekPart(uid: Long, section: String, offset: Int, length: Int): ByteArray = unused()

    override suspend fun fetchRfc822(uid: Long): ByteArray = bytes[uid] ?: ByteArray(0)

    override suspend fun storeFlags(uids: List<Long>, add: Set<String>, remove: Set<String>) {
        stored.add(uids.toList() to add)
    }

    override suspend fun uidExpunge(uids: List<Long>) {
        expunged = uids.toList()
    }

    override suspend fun uidExpungeDeleted() {
        deletedExpunges += 1
    }

    override suspend fun copyThenDelete(uids: List<Long>, targetMailbox: String) = Unit

    override suspend fun searchText(query: String): List<Long> = unused()

    override suspend fun searchCriterion(kind: String, argument: String): List<Long> = unused()

    override suspend fun sort(key: SortKey, newestFirst: Boolean): List<Long> = unused()

    override suspend fun thread(key: SortKey): ThreadNode = unused()

    override suspend fun watch(mailbox: String, onChange: (MailboxChange) -> Unit) = Unit

    override suspend fun stopWatch() = Unit

    override suspend fun append(mailbox: String, rfc822: ByteArray, flags: Set<String>) = Unit

    override suspend fun appendReturningUid(mailbox: String, rfc822: ByteArray, flags: Set<String>): Long {
        appendCount += 1
        appended = rfc822
        val id = (uids.maxOrNull() ?: 0L) + 1L
        uids.add(id)
        bytes[id] = rfc822
        return id
    }

    override suspend fun smtpSend(rfc822: ByteArray, recipients: List<String>) = Unit

    override fun close() = Unit

    private fun unused(): Nothing = throw MailFailure("not used")
}
