package org.dlang.liveimap.ui.folder

import org.dlang.liveimap.session.FolderEntry
import org.dlang.liveimap.session.IndexRequest
import org.dlang.liveimap.session.IndexRow
import org.dlang.liveimap.session.MailFailure
import org.dlang.liveimap.session.MailSession
import org.dlang.liveimap.session.MailboxChange
import org.dlang.liveimap.session.MimePart
import org.dlang.liveimap.session.Namespace
import org.dlang.liveimap.session.NamespaceKind
import org.dlang.liveimap.session.OpenResult
import org.dlang.liveimap.session.SelectResult
import org.dlang.liveimap.session.ThreadNode
import org.dlang.liveimap.settings.AccountSettings
import org.dlang.liveimap.settings.SettingsStore
import org.dlang.liveimap.settings.SortKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

class FolderTreeTest {
    @Test
    fun inboxSentMailStaysChildOfInbox() {
        val session = FakeMailSession(
            namespaces = listOf(
                Namespace("", '.', NamespaceKind.Personal),
                Namespace("user.", '.', NamespaceKind.Other),
                Namespace("#shared.", '.', NamespaceKind.Shared),
            ),
            levels = mapOf(
                ListCall("", null) to listOf(
                    FolderEntry("Archive", "Archive", false, '.'),
                    FolderEntry("INBOX", "INBOX", true, '.'),
                    FolderEntry("INBOX.sent-mail", "sent-mail", false, '.'),
                ),
                ListCall("", "INBOX") to listOf(
                    FolderEntry("INBOX.sent-mail", "sent-mail", false, '.'),
                ),
            ),
        )
        val store = MemorySettingsStore(
            AccountSettings(expandedFolders = setOf("INBOX")),
        )
        val rows = runImmediate { FolderListModel(session, store).loadLevel() }
        val sent = rows.single { it.mailbox == "INBOX.sent-mail" }
        assertEquals("INBOX", sent.parentMailbox)
        assertEquals("sent-mail", sent.leaf)
        assertEquals(1, sent.depth)
        assertTrue(rows.none { it.depth == 0 && it.mailbox == "INBOX.sent-mail" })
        assertTrue(rows.none { it.mailbox == "sent-mail" })
        assertEquals("user.", rows.single { it.mailbox == "user." }.leaf)
        assertEquals("#shared.", rows.single { it.mailbox == "#shared." }.leaf)
        assertTrue(rows.none { it.leaf == "Other Users" || it.leaf == "Shared Mailboxes" })
        assertEquals(
            listOf(
                FolderRow("INBOX", "INBOX", true, 0, null, true, delimiter = '.'),
                FolderRow("INBOX.sent-mail", "sent-mail", false, 1, "INBOX", false, delimiter = '.'),
                FolderRow("Archive", "Archive", false, 0, null, false, delimiter = '.'),
                FolderRow("user.", "user.", true, 0, null, false, namespaceRoot = true, delimiter = '.'),
                FolderRow("#shared.", "#shared.", true, 0, null, false, namespaceRoot = true, delimiter = '.'),
            ),
            rows,
        )
        assertEquals(
            listOf(ListCall("", null), ListCall("", "INBOX")),
            session.listCalls,
        )
        assertEquals(1, session.namespaceCalls)
    }

    @Test
    fun inboxDotPrefixDoesNotPromoteChildren() {
        val session = FakeMailSession(
            namespaces = listOf(
                Namespace("INBOX.", '.', NamespaceKind.Personal),
                Namespace("#shared.", '.', NamespaceKind.Shared),
            ),
            levels = mapOf(
                ListCall("INBOX.", null) to listOf(
                    FolderEntry("INBOX.sent-mail", "sent-mail", false, '.'),
                ),
            ),
        )
        val store = MemorySettingsStore(AccountSettings())
        val model = FolderListModel(session, store)
        val collapsed = runImmediate { model.loadLevel() }
        assertEquals(listOf("INBOX", "#shared."), collapsed.map { it.mailbox })
        assertTrue(collapsed.none { it.mailbox == "INBOX.sent-mail" || it.leaf == "sent-mail" })
        assertEquals("#shared.", collapsed.single { it.mailbox == "#shared." }.leaf)

        store.settings = AccountSettings(expandedFolders = setOf("INBOX"))
        val rows = runImmediate { model.loadLevel() }
        val sent = rows.single { it.mailbox == "INBOX.sent-mail" }
        assertEquals("INBOX", sent.parentMailbox)
        assertEquals("sent-mail", sent.leaf)
        assertEquals(
            listOf(
                FolderRow("INBOX", "INBOX", true, 0, null, true, delimiter = '.'),
                FolderRow("INBOX.sent-mail", "sent-mail", false, 1, "INBOX", false, delimiter = '.'),
                FolderRow("#shared.", "#shared.", true, 0, null, false, namespaceRoot = true, delimiter = '.'),
            ),
            rows,
        )
        assertTrue(session.listCalls.none { it.parentMailbox != null })
        assertEquals(
            listOf(ListCall("INBOX.", null), ListCall("INBOX.", null)),
            session.listCalls,
        )
    }

    @Test
    fun toggleExpandedPersistsInSettings() {
        val session = FakeMailSession(namespaces = emptyList(), levels = emptyMap())
        val store = MemorySettingsStore(
            AccountSettings(imapHost = "imap.example.com"),
        )
        val model = FolderListModel(session, store)
        runImmediate { model.toggleExpanded("INBOX") }
        assertEquals(setOf("INBOX"), store.settings.expandedFolders)
        assertEquals("imap.example.com", store.settings.imapHost)
        assertEquals(1, store.saves)
        runImmediate { model.toggleExpanded("INBOX") }
        assertEquals(emptySet<String>(), store.settings.expandedFolders)
        runImmediate { model.toggleExpanded("Archive") }
        runImmediate { model.toggleExpanded("INBOX") }
        assertEquals(setOf("Archive", "INBOX"), store.settings.expandedFolders)
        assertEquals(0, session.namespaceCalls)
        assertTrue(session.listCalls.isEmpty())
    }

    @Test
    fun listFailurePropagatesServerText() {
        val session = FakeMailSession(
            namespaces = listOf(Namespace("", '.', NamespaceKind.Personal)),
            levels = emptyMap(),
            listFailure = MailFailure("NO Permission denied"),
        )
        val model = FolderListModel(session, MemorySettingsStore(AccountSettings()))
        try {
            runImmediate { model.loadLevel() }
        } catch (error: MailFailure) {
            assertEquals("NO Permission denied", error.text)
            return
        }
        fail("expected MailFailure")
    }

    @Test
    fun zetaAlphaInboxOrdersInboxFirstAndChildrenAlphabetically() {
        val session = FakeMailSession(
            namespaces = listOf(Namespace("", '.', NamespaceKind.Personal)),
            levels = mapOf(
                ListCall("", null) to listOf(
                    FolderEntry("zeta", "zeta", true, '.'),
                    FolderEntry("alpha", "alpha", false, '.'),
                    FolderEntry("INBOX", "INBOX", false, '.'),
                ),
                ListCall("", "zeta") to listOf(
                    FolderEntry("zeta.b", "b", false, '.'),
                    FolderEntry("zeta.a", "a", false, '.'),
                ),
            ),
        )
        val store = MemorySettingsStore(
            AccountSettings(expandedFolders = setOf("zeta")),
        )
        val rows = runImmediate { FolderListModel(session, store).loadLevel() }
        assertEquals(listOf("INBOX", "alpha", "zeta"), rows.filter { it.depth == 0 }.map { it.mailbox })
        assertEquals(listOf("a", "b"), rows.filter { it.parentMailbox == "zeta" }.map { it.leaf })
    }

    @Test
    fun refreshVisibleCounts() {
        var now = 5_000L
        val session = FakeMailSession(namespaces = emptyList(), levels = emptyMap())
        session.statusCounts = mapOf("INBOX" to 12)
        val model = FolderListModel(session, MemorySettingsStore(AccountSettings())) { now }
        val visible = listOf(
            FolderRow("user.", "user.", true, 0, null, false, namespaceRoot = true, delimiter = '.'),
            FolderRow("INBOX", "INBOX", true, 0, null, false, unseen = 3, delimiter = '.'),
        )
        val first = runImmediate { model.refreshVisibleCounts(visible) }
        assertEquals(listOf(listOf("INBOX")), session.statusCalls)
        assertEquals(12, first.single { it.mailbox == "INBOX" }.messages)
        assertEquals(3, first.single { it.mailbox == "INBOX" }.unseen)
        assertEquals(null, first.single { it.namespaceRoot }.messages)
        val second = runImmediate { model.refreshVisibleCounts(visible) }
        assertEquals(listOf(listOf("INBOX")), session.statusCalls)
        assertEquals(12, second.single { it.mailbox == "INBOX" }.messages)
        now += 300_000L
        val third = runImmediate { model.refreshVisibleCounts(visible) }
        assertEquals(listOf(listOf("INBOX"), listOf("INBOX")), session.statusCalls)
        assertEquals(12, third.single { it.mailbox == "INBOX" }.messages)
        assertEquals(3, third.single { it.mailbox == "INBOX" }.unseen)
    }
}

private data class ListCall(val prefix: String, val parentMailbox: String?)

private class MemorySettingsStore(initial: AccountSettings) : SettingsStore {
    var settings: AccountSettings = initial
    var saves: Int = 0

    override suspend fun load(): AccountSettings = settings

    override suspend fun save(settings: AccountSettings) {
        saves += 1
        this.settings = settings
    }

    override suspend fun password(): String = ""

    override suspend fun setPassword(value: String) = Unit
}

private class FakeMailSession(
    private val namespaces: List<Namespace>,
    private val levels: Map<ListCall, List<FolderEntry>>,
    private val listFailure: MailFailure? = null,
) : MailSession {
    val listCalls = mutableListOf<ListCall>()
    val statusCalls = mutableListOf<List<String>>()
    var statusCounts: Map<String, Int> = emptyMap()
    var namespaceCalls: Int = 0

    override val capabilities: Set<String> = emptySet()

    override suspend fun open(account: AccountSettings): OpenResult = unused()

    override suspend fun namespaces(): List<Namespace> {
        namespaceCalls += 1
        return namespaces
    }

    override suspend fun listLevel(prefix: String, parentMailbox: String?, unreadCounts: Boolean): List<FolderEntry> {
        val call = ListCall(prefix, parentMailbox)
        listCalls += call
        val failure = listFailure
        if (failure != null) throw failure
        return levels[call] ?: emptyList()
    }

    override suspend fun statusMessages(mailboxes: List<String>): Map<String, Int> {
        statusCalls += mailboxes.toList()
        return statusCounts.filterKeys { it in mailboxes }
    }

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

private fun <T> runImmediate(block: suspend () -> T): T {
    var result: Result<T>? = null
    block.startCoroutine(Continuation(EmptyCoroutineContext) { result = it })
    return result!!.getOrThrow()
}
