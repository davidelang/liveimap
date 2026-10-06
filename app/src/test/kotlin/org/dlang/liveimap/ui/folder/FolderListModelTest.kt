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
import org.junit.Test
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

class FolderListModelTest {
    @Test
    fun expandCollapseAndCollapseAllLeaveStoredExpandedFolders() {
        val session = treeSession()
        val store = ModelSettingsStore(
            AccountSettings(expandedFolders = setOf("INBOX"), imapHost = "imap.example.com"),
        )
        val model = FolderListModel(session, store)
        val opened = runModel { model.loadLevel() }
        assertTrue(opened.single { it.mailbox == "INBOX" }.expanded)
        assertTrue(opened.any { it.mailbox == "INBOX.sent" })

        runModel { model.toggleExpanded("Archive") }
        assertEquals(setOf("INBOX"), store.settings.expandedFolders)
        assertEquals(0, store.saves)
        assertTrue(runModel { model.loadLevel() }.any { it.mailbox == "Archive.b" })

        runModel { model.toggleExpanded("Archive") }
        assertEquals(setOf("INBOX"), store.settings.expandedFolders)
        assertEquals(0, store.saves)
        assertTrue(runModel { model.loadLevel() }.none { it.mailbox == "Archive.b" })

        runModel { model.showCollapsed("INBOX.sent") }
        assertEquals(setOf("INBOX"), store.settings.expandedFolders)
        assertEquals(0, store.saves)

        runModel { model.collapseAll() }
        assertEquals(setOf("INBOX"), store.settings.expandedFolders)
        assertEquals("imap.example.com", store.settings.imapHost)
        assertEquals(0, store.saves)
        val collapsed = runModel { model.loadLevel() }
        assertTrue(collapsed.none { it.depth > 0 || it.expanded })
    }

    @Test
    fun listingOneLevelTwiceCallsListLevelOnceUntilRefresh() {
        val session = treeSession()
        val model = FolderListModel(session, ModelSettingsStore(AccountSettings()))
        runModel { model.loadLevel() }
        runModel { model.loadLevel() }
        assertEquals(listOf(ModelListCall("", null)), session.listCalls)

        runModel { model.toggleExpanded("INBOX") }
        runModel { model.loadLevel() }
        runModel { model.loadLevel() }
        assertEquals(listOf(ModelListCall("", null), ModelListCall("", "INBOX")), session.listCalls)

        model.refreshLevels()
        runModel { model.loadLevel() }
        assertEquals(
            listOf(
                ModelListCall("", null),
                ModelListCall("", "INBOX"),
                ModelListCall("", null),
                ModelListCall("", "INBOX"),
            ),
            session.listCalls,
        )
    }

    @Test
    fun collapsedRowKeepsTheCountItHadWhenListed() {
        val session = treeSession()
        val model = FolderListModel(
            session,
            ModelSettingsStore(AccountSettings(expandedFolders = setOf("INBOX"))),
        )
        val opened = runModel { model.loadLevel() }
        assertEquals(5, opened.single { it.mailbox == "INBOX" }.messages)
        assertEquals(9, opened.single { it.mailbox == "INBOX.sent" }.messages)
        assertEquals(1, opened.single { it.mailbox == "INBOX.sent" }.unseen)

        runModel { model.toggleExpanded("INBOX") }
        val collapsed = runModel { model.loadLevel() }
        assertTrue(collapsed.none { it.mailbox == "INBOX.sent" })
        assertEquals(5, collapsed.single { it.mailbox == "INBOX" }.messages)
        assertEquals(false, collapsed.single { it.mailbox == "INBOX" }.expanded)

        runModel { model.toggleExpanded("INBOX") }
        val again = runModel { model.loadLevel() }
        assertEquals(5, again.single { it.mailbox == "INBOX" }.messages)
        assertEquals(9, again.single { it.mailbox == "INBOX.sent" }.messages)
        assertEquals(1, again.single { it.mailbox == "INBOX.sent" }.unseen)
        assertEquals(listOf(ModelListCall("", null), ModelListCall("", "INBOX")), session.listCalls)
    }

    @Test
    fun searchKeepsEmptyPrefixAndExcludesMisses() {
        val empty = FolderRow("", "ignored", true, 0, null, false, namespaceRoot = true)
        val archive = FolderRow("Archive", "Archive", false, 0, null, false)
        val inbox = FolderRow("INBOX", "INBOX", false, 0, null, false)
        assertEquals("(empty prefix)", folderDisplayName(empty, "(empty prefix)"))
        assertEquals(listOf(""), folderRowsMatchingName(listOf(empty, archive, inbox), "zzz", "(empty prefix)").map { it.mailbox })
        assertEquals(listOf(""), folderRowsMatchingName(listOf(empty, archive), "ignored", "(empty prefix)").map { it.mailbox })
        assertEquals(listOf("", "Archive"), folderRowsMatchingName(listOf(empty, archive, inbox), "arch", "(empty prefix)").map { it.mailbox })
    }

    @Test
    fun saveResetAndAlwaysExpand() {
        val session = treeSession()
        val store = ModelSettingsStore(
            AccountSettings(expandedFolders = setOf("INBOX"), imapHost = "imap.example.com"),
        )
        val model = FolderListModel(session, store)
        runModel { model.loadLevel() }
        runModel { model.toggleExpanded("Archive") }
        runModel { model.saveDefaultView() }
        assertEquals(setOf("INBOX", "Archive"), store.settings.expandedFolders)
        assertEquals("imap.example.com", store.settings.imapHost)
        assertEquals(1, store.saves)

        runModel { model.collapseAll() }
        assertTrue(runModel { model.loadLevel() }.none { it.expanded })
        assertEquals(setOf("INBOX", "Archive"), store.settings.expandedFolders)
        assertEquals(1, store.saves)

        runModel { model.resetToDefault() }
        val reset = runModel { model.loadLevel() }
        assertTrue(reset.single { it.mailbox == "INBOX" }.expanded)
        assertTrue(reset.single { it.mailbox == "Archive" }.expanded)
        assertEquals(1, store.saves)

        runModel { model.dontAlwaysExpand("INBOX") }
        assertEquals(setOf("Archive"), store.settings.expandedFolders)
        assertEquals("imap.example.com", store.settings.imapHost)
        assertTrue(runModel { model.loadLevel() }.single { it.mailbox == "INBOX" }.expanded)

        runModel { model.alwaysExpand("Sent") }
        assertEquals(setOf("Archive", "Sent"), store.settings.expandedFolders)
        assertEquals("imap.example.com", store.settings.imapHost)
        assertTrue(runModel { model.loadLevel() }.single { it.mailbox == "Sent" }.expanded)
    }
}

private fun treeSession(): ModelMailSession = ModelMailSession(
    namespaces = listOf(Namespace("", '.', NamespaceKind.Personal)),
    levels = mapOf(
        ModelListCall("", null) to listOf(
            FolderEntry("INBOX", "INBOX", true, '.', messages = 5, unseen = 2),
            FolderEntry("Archive", "Archive", true, '.'),
            FolderEntry("Sent", "Sent", true, '.'),
        ),
        ModelListCall("", "INBOX") to listOf(
            FolderEntry("INBOX.sent", "sent", false, '.', messages = 9, unseen = 1),
        ),
        ModelListCall("", "Archive") to listOf(
            FolderEntry("Archive.b", "b", false, '.', messages = 4),
        ),
        ModelListCall("", "Sent") to listOf(
            FolderEntry("Sent.x", "x", false, '.'),
        ),
    ),
)

private data class ModelListCall(val prefix: String, val parentMailbox: String?)

private class ModelSettingsStore(initial: AccountSettings) : SettingsStore {
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

private class ModelMailSession(
    private val namespaces: List<Namespace>,
    private val levels: Map<ModelListCall, List<FolderEntry>>,
) : MailSession {
    val listCalls = mutableListOf<ModelListCall>()

    override val capabilities: Set<String> = emptySet()

    override suspend fun open(account: AccountSettings): OpenResult = unused()

    override suspend fun namespaces(): List<Namespace> = namespaces

    override suspend fun listLevel(prefix: String, parentMailbox: String?, unreadCounts: Boolean): List<FolderEntry> {
        val call = ModelListCall(prefix, parentMailbox)
        listCalls += call
        return levels[call] ?: emptyList()
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

private fun <T> runModel(block: suspend () -> T): T {
    var result: Result<T>? = null
    block.startCoroutine(Continuation(EmptyCoroutineContext) { result = it })
    return result!!.getOrThrow()
}
