package org.dlang.liveimap.ui.index

import org.dlang.liveimap.session.ComposeKind
import org.dlang.liveimap.session.ComposeSeed
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
import org.dlang.liveimap.session.ThreadNode
import org.dlang.liveimap.settings.AccountSettings
import org.dlang.liveimap.settings.Density
import org.dlang.liveimap.settings.FolderView
import org.dlang.liveimap.settings.SettingsStore
import org.dlang.liveimap.settings.SortKey
import org.dlang.liveimap.settings.SwipeAction
import org.dlang.liveimap.settings.SwipeBinding
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

class IndexWindowTest {
    @Test
    fun arrivalNewestRequestsArrivalModeAndDoesNotSort() {
        val session = FakeMailSession()
        session.arrivalRows = listOf(row(3, sequence = 3), row(8, sequence = 8))
        val store = MemorySettingsStore(AccountSettings())
        val model = IndexModel(session, store, "INBOX")
        val rows = runImmediate { model.loadWindow() }
        val request = session.fetchRequests.single()
        assertEquals(IndexMode.ArrivalNewest, request.mode)
        assertEquals("INBOX", request.mailbox)
        assertEquals(IndexPageSize, request.limit)
        assertEquals(IndexPageSize, request.prefetch)
        assertTrue(request.uids.isEmpty())
        assertTrue(!request.includePreview)
        assertTrue(session.sortCalls.isEmpty())
        assertTrue(session.threadCalls.isEmpty())
        assertEquals(listOf(3L, 8L), rows.map { it.uid })
        assertEquals(0, session.rfc822Count)
        assertEquals(0, session.structureCount)
        assertEquals(0, session.peekCount)
    }

    @Test
    fun arrivalOldestDoesNotCallSort() {
        val session = FakeMailSession()
        val store = MemorySettingsStore(
            AccountSettings(defaultView = FolderView(SortKey.Arrival, newestFirst = false)),
        )
        val model = IndexModel(session, store, "INBOX")
        runImmediate { model.loadWindow() }
        assertEquals(IndexMode.ArrivalOldest, session.fetchRequests.single().mode)
        assertTrue(session.sortCalls.isEmpty())
    }

    @Test
    fun perFolderViewIsUsedAndSortFetchesVisibleSlice() {
        val session = FakeMailSession()
        val uids = (1L..10L).toList()
        session.sortUids = uids
        uids.forEach { session.rows[it] = row(it) }
        val store = MemorySettingsStore(
            AccountSettings(
                defaultView = FolderView(SortKey.Arrival, newestFirst = true),
                folderViews = mapOf("INBOX" to FolderView(SortKey.Date, newestFirst = false)),
            ),
        )
        val model = IndexModel(session, store, "INBOX")
        val rows = runImmediate { model.loadWindow() }
        assertEquals(listOf(SortKey.Date to false), session.sortCalls)
        val request = session.fetchRequests.single()
        assertEquals(IndexMode.ByUid, request.mode)
        assertEquals(uids, request.uids)
        assertTrue(!request.includePreview)
        assertEquals(uids, rows.map { it.uid })
    }

    @Test
    fun applyViewWritesOnlyThisMailbox() {
        val session = FakeMailSession()
        session.sortUids = listOf(4L, 5L)
        session.rows[4L] = row(4)
        session.rows[5L] = row(5)
        val original = AccountSettings(
            defaultView = FolderView(SortKey.Arrival, newestFirst = true),
            folderViews = mapOf("INBOX" to FolderView(SortKey.Subject, newestFirst = true)),
            expandedFolders = setOf("INBOX"),
        )
        val store = MemorySettingsStore(original)
        val model = IndexModel(session, store, "Archive")
        runImmediate { model.applyView(FolderView(SortKey.From, newestFirst = false)) }
        assertEquals(FolderView(SortKey.From, newestFirst = false), store.settings.folderViews["Archive"])
        assertEquals(FolderView(SortKey.Subject, newestFirst = true), store.settings.folderViews["INBOX"])
        assertEquals(original.defaultView, store.settings.defaultView)
        assertEquals(original.expandedFolders, store.settings.expandedFolders)
        assertEquals(listOf(SortKey.From to false), session.sortCalls)
        assertEquals(listOf(4L, 5L), session.fetchRequests.single().uids)
    }

    @Test
    fun threadViewOldestFirst() {
        val session = FakeMailSession()
        val tree = ThreadNode(
            uid = null,
            children = listOf(
                ThreadNode(5L, listOf(ThreadNode(1L, emptyList()))),
                ThreadNode(3L, emptyList()),
                ThreadNode(9L, listOf(ThreadNode(4L, emptyList()), ThreadNode(2L, emptyList()))),
            ),
        )
        session.threadNode = tree
        listOf(5L, 1L, 3L, 9L, 4L, 2L).forEach { session.rows[it] = row(it) }
        val store = MemorySettingsStore(
            AccountSettings(defaultView = FolderView(SortKey.ThreadReferences, newestFirst = false)),
        )
        val model = IndexModel(session, store, "INBOX")
        val rows = runImmediate { model.loadWindow() }
        assertEquals(listOf(3L, 5L, 9L), rows.map { it.uid })
        assertEquals(listOf(SortKey.ThreadReferences), session.threadCalls)
        assertTrue(session.sortCalls.isEmpty())
        assertEquals(2, session.fetchRequests.size)
        assertEquals(IndexMode.ByUid, session.fetchRequests[0].mode)
        assertEquals(listOf(3L, 5L, 9L), session.fetchRequests[0].uids)
        val hidden = session.fetchRequests[1]
        assertEquals(IndexMode.ByUid, hidden.mode)
        assertEquals(listOf(1L, 4L, 2L), hidden.uids)
        assertTrue(!hidden.includePreview)
        assertNull(model.summaries[3L])
        assertEquals(1, model.summaries.getValue(5L).hidden)
        assertEquals(2, model.summaries.getValue(9L).hidden)
        assertEquals(0, session.rfc822Count)
        assertEquals(0, session.structureCount)
        assertEquals(0, session.peekCount)
    }

    @Test
    fun threadNewestFirstPutsLargestRootFirst() {
        val session = FakeMailSession(capabilities = setOf("THREAD=ORDEREDSUBJECT"))
        session.threadNode = ThreadNode(
            uid = null,
            children = listOf(ThreadNode(2L, emptyList()), ThreadNode(8L, listOf(ThreadNode(1L, emptyList())))),
        )
        listOf(2L, 8L, 1L).forEach { session.rows[it] = row(it) }
        val store = MemorySettingsStore(
            AccountSettings(defaultView = FolderView(SortKey.ThreadOrderedSubject, newestFirst = true)),
        )
        val model = IndexModel(session, store, "INBOX")
        val rows = runImmediate { model.loadWindow() }
        assertEquals(listOf(8L, 2L), rows.map { it.uid })
        assertEquals(1, model.summaries.getValue(8L).hidden)
        assertTrue(session.sortCalls.isEmpty())
        assertEquals(listOf(SortKey.ThreadOrderedSubject), session.threadCalls)
    }

    @Test
    fun orderedSubjectHiddenUnlessAdvertised() {
        val hidden = IndexModel(FakeMailSession(capabilities = setOf("THREAD=REFERENCES")), MemorySettingsStore(AccountSettings()), "INBOX")
        assertTrue(SortKey.ThreadOrderedSubject !in hidden.menuKeys)
        assertTrue(SortKey.ThreadReferences in hidden.menuKeys)
        val shown = IndexModel(
            FakeMailSession(capabilities = setOf("thread=orderedsubject")),
            MemorySettingsStore(AccountSettings()),
            "INBOX",
        )
        assertTrue(SortKey.ThreadOrderedSubject in shown.menuKeys)
    }

    @Test
    fun compactIssuesNoPreviewAndMediumDoes() {
        assertEquals(0, previewLineCount(Density.Compact))
        assertEquals(3, previewLineCount(Density.Medium))
        assertEquals(7, previewLineCount(Density.Large))
        val compact = FakeMailSession()
        runImmediate { IndexModel(compact, MemorySettingsStore(AccountSettings()), "INBOX").loadWindow() }
        assertTrue(!compact.fetchRequests.single().includePreview)
        val medium = FakeMailSession()
        val store = MemorySettingsStore(AccountSettings(density = Density.Medium))
        runImmediate { IndexModel(medium, store, "INBOX").loadWindow() }
        assertTrue(medium.fetchRequests.single().includePreview)
        assertEquals(0, medium.structureCount)
        assertEquals(0, medium.peekCount)
        assertEquals(0, medium.rfc822Count)
    }

    @Test
    fun deleteAddsDeletedAndDoesNotCopy() {
        val session = FakeMailSession()
        session.arrivalRows = listOf(row(6, flags = setOf("\\Seen")))
        val model = IndexModel(session, MemorySettingsStore(AccountSettings()), "INBOX")
        runImmediate { model.loadWindow() }
        val command = runImmediate { model.performSwipe(6L, SwipeBinding(SwipeAction.Delete)) }
        assertTrue(command is IndexCommand.None)
        assertEquals(listOf(FlagWrite(listOf(6L), setOf("\\Deleted"), emptySet())), session.stores)
        assertTrue(session.copies.isEmpty())
        assertEquals(setOf("\\Seen", "\\Deleted"), model.rows.single().flags)
        assertEquals(0, session.rfc822Count)
    }

    @Test
    fun moveRequiresMailbox() {
        val session = FakeMailSession()
        session.arrivalRows = listOf(row(1))
        val model = IndexModel(session, MemorySettingsStore(AccountSettings()), "INBOX")
        runImmediate { model.loadWindow() }
        runImmediate { model.moveMessages(listOf(1L), "") }
        assertEquals("Move folder is not set", model.notice)
        assertTrue(session.copies.isEmpty())
        assertEquals(listOf(1L), model.rows.map { it.uid })
        runImmediate { model.moveMessages(listOf(1L), "Trash") }
        assertEquals(listOf(CopyWrite(listOf(1L), "Trash")), session.copies)
        assertTrue(session.stores.isEmpty())
        assertTrue(model.rows.isEmpty())
    }

    @Test
    fun replyDoesNotBuildAMessage() {
        val session = FakeMailSession()
        val model = IndexModel(session, MemorySettingsStore(AccountSettings()), "INBOX")
        val reply = runImmediate { model.performSwipe(4L, SwipeBinding(SwipeAction.Reply)) }
        val all = runImmediate { model.performSwipe(4L, SwipeBinding(SwipeAction.ReplyAll)) }
        assertEquals(IndexCommand.Compose(ComposeSeed(ComposeKind.Reply, "INBOX", listOf(4L))), reply)
        assertEquals(IndexCommand.Compose(ComposeSeed(ComposeKind.ReplyAll, "INBOX", listOf(4L))), all)
        assertEquals(ComposeSeed(ComposeKind.Bounce, "INBOX", listOf(4L, 5L)), model.bounceSeed(listOf(4L, 5L)))
        assertEquals(0, session.rfc822Count)
        assertEquals(0, session.appendCount)
        assertTrue(session.stores.isEmpty())
    }

    @Test
    fun postponedMailboxOpensResumeInsteadOfReader() {
        val session = FakeMailSession()
        val store = MemorySettingsStore(AccountSettings(postponedMailbox = "Drafts"))
        val drafts = IndexModel(session, store, "Drafts")
        runImmediate { drafts.loadWindow() }
        assertEquals(ComposeSeed(ComposeKind.ResumePostpone, "Drafts", listOf(8L)), drafts.openSeed(8L))
        val inbox = IndexModel(session, store, "INBOX")
        runImmediate { inbox.loadWindow() }
        assertNull(inbox.openSeed(8L))
    }

    @Test
    fun emptySearchDoesNotCallSearchOrFilterLocally() {
        val session = FakeMailSession()
        session.arrivalRows = listOf(row(1), row(2), row(3))
        session.searchUids = listOf(9L, 8L)
        session.rows[9L] = row(9)
        session.rows[8L] = row(8)
        val model = IndexModel(session, MemorySettingsStore(AccountSettings()), "INBOX")
        runImmediate { model.loadWindow() }
        val fetches = session.fetchRequests.size
        runImmediate { model.applySearch("") }
        assertTrue(session.searchCalls.isEmpty())
        assertEquals(fetches, session.fetchRequests.size)
        assertEquals(listOf(1L, 2L, 3L), model.rows.map { it.uid })
        val searched = runImmediate { model.applySearch("needle") }
        assertEquals(listOf("needle"), session.searchCalls)
        assertEquals(IndexMode.ByUid, session.fetchRequests.last().mode)
        assertEquals(listOf(9L, 8L), session.fetchRequests.last().uids)
        assertEquals(listOf(9L, 8L), searched.map { it.uid })
        runImmediate { model.applySearch("") }
        assertEquals(listOf("needle"), session.searchCalls)
        assertEquals(listOf(1L, 2L, 3L), model.rows.map { it.uid })
    }

    @Test
    fun mailFailureLeavesPreviousRows() {
        val session = FakeMailSession()
        session.arrivalRows = listOf(row(1, sequence = 4))
        val model = IndexModel(session, MemorySettingsStore(AccountSettings()), "INBOX")
        runImmediate { model.loadWindow() }
        session.failure = MailFailure("nope")
        runImmediate { model.applyView(FolderView(SortKey.Date, newestFirst = true)) }
        assertEquals("nope", model.notice)
        assertEquals(listOf(1L), model.rows.map { it.uid })
        assertEquals(4, model.rows.single().sequence)
    }

    @Test
    fun flagsUpdateDoesNotReuseSequence() {
        val session = FakeMailSession()
        session.arrivalRows = listOf(row(7, sequence = 4, flags = setOf("\\Seen")))
        val model = IndexModel(session, MemorySettingsStore(AccountSettings()), "INBOX")
        runImmediate { model.loadWindow() }
        runImmediate { model.applyChange(MailboxChange.Flags(7L, setOf("\\Flagged"))) }
        assertEquals(setOf("\\Flagged"), model.rows.single().flags)
        assertEquals(4, model.rows.single().sequence)
        session.arrivalRows = listOf(row(7, sequence = 1, flags = setOf("\\Flagged")))
        runImmediate { model.applyChange(MailboxChange.Exists(2)) }
        assertEquals(1, model.rows.single().sequence)
        assertEquals(IndexMode.ArrivalNewest, session.fetchRequests.last().mode)
    }

    @Test
    fun pagingDropsThePageThatLeft() {
        val session = FakeMailSession()
        val uids = (1L..130L).toList()
        session.sortUids = uids
        uids.forEach { session.rows[it] = row(it) }
        val store = MemorySettingsStore(
            AccountSettings(defaultView = FolderView(SortKey.Size, newestFirst = true)),
        )
        val model = IndexModel(session, store, "INBOX")
        runImmediate { model.loadWindow() }
        assertEquals(uids.take(IndexPageSize), session.fetchRequests[0].uids)
        assertEquals(uids.drop(IndexPageSize).take(IndexPageSize), session.fetchRequests[1].uids)
        assertEquals(uids.take(IndexPageSize * 2), model.rows.map { it.uid })
        runImmediate { model.onFirstVisible(0) }
        runImmediate { model.onFirstVisible(IndexPageSize) }
        assertTrue(model.rows.none { it.uid <= IndexPageSize })
        assertEquals(uids.drop(IndexPageSize), model.rows.map { it.uid })
        assertEquals(SortKey.Size to true, session.sortCalls.single())
    }

    @Test
    fun sortKeysCallSortExceptArrivalAndThread() {
        val keys = listOf(SortKey.Date, SortKey.From, SortKey.Subject, SortKey.To, SortKey.Cc, SortKey.Size)
        for (key in keys) {
            val session = FakeMailSession()
            session.sortUids = listOf(1L)
            session.rows[1L] = row(1)
            val store = MemorySettingsStore(AccountSettings(defaultView = FolderView(key, newestFirst = true)))
            runImmediate { IndexModel(session, store, "INBOX").loadWindow() }
            assertEquals(listOf(key to true), session.sortCalls)
            assertEquals(IndexMode.ByUid, session.fetchRequests.single().mode)
            assertTrue(session.threadCalls.isEmpty())
        }
    }

    @Test
    fun swipeRequiresFortyPercentAndMapsTrailing() {
        val trailing = SwipeBinding(SwipeAction.Delete)
        val leading = SwipeBinding(SwipeAction.ReplyAll)
        assertNull(indexSwipeBinding(-39f, 100f, leftToRight = true, multiSelect = false, trailing, leading))
        assertEquals(trailing, indexSwipeBinding(-40f, 100f, leftToRight = true, multiSelect = false, trailing, leading))
        assertEquals(leading, indexSwipeBinding(40f, 100f, leftToRight = true, multiSelect = false, trailing, leading))
        assertEquals(trailing, indexSwipeBinding(100f, 250f, leftToRight = false, multiSelect = false, trailing, leading))
        assertEquals(leading, indexSwipeBinding(-100f, 250f, leftToRight = false, multiSelect = false, trailing, leading))
        assertNull(indexSwipeBinding(-100f, 100f, leftToRight = true, multiSelect = true, trailing, leading))
        assertNull(indexSwipeBinding(-100f, 0f, leftToRight = true, multiSelect = false, trailing, leading))
        assertEquals("", barMoveMailbox(AccountSettings()))
        val move = AccountSettings(
            swipeTrailing = SwipeBinding(SwipeAction.Move, moveMailbox = ""),
            swipeLeading = SwipeBinding(SwipeAction.Move, moveMailbox = "Archive"),
        )
        assertEquals("Archive", barMoveMailbox(move))
    }

    @Test
    fun swipeBindingForOffsetAndVisual() {
        val trailing = SwipeBinding(SwipeAction.Delete)
        val leading = SwipeBinding(SwipeAction.ReplyAll)
        assertNull(swipeBindingForOffset(0f, leftToRight = true, trailing, leading))
        assertEquals(trailing, swipeBindingForOffset(-1f, leftToRight = true, trailing, leading))
        assertEquals(leading, swipeBindingForOffset(1f, leftToRight = true, trailing, leading))
        assertEquals(trailing, swipeBindingForOffset(1f, leftToRight = false, trailing, leading))
        assertEquals(leading, swipeBindingForOffset(-1f, leftToRight = false, trailing, leading))
        assertEquals(SwipeVisual("errorContainer", "delete"), swipeVisual(SwipeAction.Delete))
        assertEquals(SwipeVisual("tertiaryContainer", "drive_file_move"), swipeVisual(SwipeAction.Move))
        assertEquals(SwipeVisual("primaryContainer", "reply"), swipeVisual(SwipeAction.Reply))
        assertEquals(SwipeVisual("primaryContainer", "reply_all"), swipeVisual(SwipeAction.ReplyAll))
        assertEquals(SwipeVisual("secondaryContainer", "flag"), swipeVisual(SwipeAction.SetFlag))
        assertEquals(SwipeVisual("secondaryContainer", "outlined_flag"), swipeVisual(SwipeAction.ClearFlag))
        assertEquals(SwipeVisual("secondaryContainer", "flag"), swipeVisual(SwipeAction.FlagScreen))
    }

    @Test
    fun indexStatusDescriptionAndSequenceWidth() {
        assertEquals(
            "forwarded, flagged",
            indexStatusDescription(
                setOf("\$Forwarded", "\\Answered", "\\Flagged"),
                toMe = false,
                hasAttachment = false,
            ),
        )
        assertEquals(
            "replied, to me",
            indexStatusDescription(setOf("\\Answered"), toMe = true, hasAttachment = false),
        )
        assertEquals(2, sequenceColumnChars(listOf(4, 80, 0)))
    }

    @Test
    fun filterChipsNarrowAndDrop() {
        val session = FakeMailSession()
        session.searchUids = listOf(1L, 2L)
        session.arrivalRows = listOf(row(1), row(2))
        val model = IndexModel(session, MemorySettingsStore(AccountSettings()), "INBOX")
        runImmediate { model.loadWindow() }
        runImmediate { model.applyCriterion("From", "ada", narrow = false, label = "From") }
        runImmediate { model.applyCriterion("Subject", "x", narrow = true, label = "Subject") }
        assertEquals(
            listOf(AppliedFilter("From", "ada"), AppliedFilter("Subject", "x")),
            model.filters,
        )
        runImmediate { model.dropFiltersFrom(model.filters.lastIndex) }
        assertEquals(listOf(AppliedFilter("From", "ada")), model.filters)
        runImmediate { model.dropFiltersFrom(0) }
        assertEquals(emptyList<AppliedFilter>(), model.filters)
    }

    @Test
    fun followingUidReturnsNextAndArrivalPublishKeepsSequences() {
        OpenMessageOrder.clear()
        assertEquals(6L, followingUid(listOf(5L, 6L, 7L), 5L))
        assertNull(followingUid(listOf(5L, 6L, 7L), 7L))
        assertNull(followingUid(listOf(5L, 6L, 7L), 9L))
        val session = FakeMailSession()
        session.arrivalRows = listOf(row(5, sequence = 11), row(6, sequence = 22))
        val model = IndexModel(session, MemorySettingsStore(AccountSettings()), "INBOX")
        val rows = runImmediate { model.loadWindow() }
        assertEquals(listOf(5L, 6L), OpenMessageOrder.uids)
        assertEquals(rows.map { it.uid }, OpenMessageOrder.uids)
        for (loaded in rows) {
            assertEquals(loaded.sequence, OpenMessageOrder.sequence(loaded.uid))
        }
        assertEquals(0, OpenMessageOrder.sequence(9L))
    }

    @Test
    fun emptyIndexTextBlankAndQuery() {
        assertEquals("No messages", emptyIndexText("", "INBOX"))
        assertEquals("No messages match \u201cclamp\u201d in INBOX", emptyIndexText("clamp", "INBOX"))
    }

    @Test
    fun selectionTitleFormatsPartialAllAndEmpty() {
        assertEquals("2 selected", selectionTitle(false, 2, 0))
        assertEquals("All 3 selected", selectionTitle(true, 0, 3))
        assertEquals("All selected", selectionTitle(true, 0, 0))
    }

    @Test
    fun selectAllTargetUsesFilterUidsOrEntireMailbox() {
        val filtered = selectAllTarget(true, listOf(2L, 5L))
        assertEquals(listOf(2L, 5L), (filtered as SelectAllTarget.Uids).uids)
        assertEquals(SelectAllTarget.EntireMailbox, selectAllTarget(false, emptyList()))
    }
}

private fun row(
    uid: Long,
    sequence: Int = uid.toInt(),
    flags: Set<String> = emptySet(),
) = IndexRow(
    uid = uid,
    sequence = sequence,
    flags = flags,
    internalDateEpoch = uid,
    size = 10,
    from = "from$uid",
    subject = "subject$uid",
    envelopeDate = "date$uid",
    preview = "preview$uid",
)

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

private data class FlagWrite(val uids: List<Long>, val add: Set<String>, val remove: Set<String>)

private data class CopyWrite(val uids: List<Long>, val target: String)

private class FakeMailSession(
    override var capabilities: Set<String> = emptySet(),
) : MailSession {
    val fetchRequests = mutableListOf<IndexRequest>()
    val sortCalls = mutableListOf<Pair<SortKey, Boolean>>()
    val threadCalls = mutableListOf<SortKey>()
    val searchCalls = mutableListOf<String>()
    val stores = mutableListOf<FlagWrite>()
    val copies = mutableListOf<CopyWrite>()
    val rows = HashMap<Long, IndexRow>()
    var arrivalRows: List<IndexRow> = emptyList()
    var sortUids: List<Long> = emptyList()
    var searchUids: List<Long> = emptyList()
    var threadNode: ThreadNode = ThreadNode(null, emptyList())
    var failure: MailFailure? = null
    var rfc822Count: Int = 0
    var structureCount: Int = 0
    var peekCount: Int = 0
    var appendCount: Int = 0
    var expungeCount: Int = 0
    val watchMailboxes = mutableListOf<String>()
    var stopWatchCount: Int = 0

    override suspend fun open(account: AccountSettings): OpenResult = unused()

    override suspend fun namespaces(): List<Namespace> = unused()

    override suspend fun listLevel(prefix: String, parentMailbox: String?, unreadCounts: Boolean): List<FolderEntry> =
        unused()

    override suspend fun select(mailbox: String): SelectResult = unused()

    override suspend fun unselect() = Unit

    override suspend fun fetchIndex(request: IndexRequest): List<IndexRow> {
        fetchRequests += request
        throwIfArmed()
        return when (request.mode) {
            IndexMode.ByUid -> request.uids.asReversed().map { uid -> rows[uid] ?: row(uid) }
            IndexMode.ArrivalNewest, IndexMode.ArrivalOldest -> arrivalRows
        }
    }

    override suspend fun fetchStructure(uid: Long): MimePart {
        structureCount += 1
        return unused()
    }

    override suspend fun peekPart(uid: Long, section: String, offset: Int, length: Int): ByteArray {
        peekCount += 1
        return unused()
    }

    override suspend fun fetchRfc822(uid: Long): ByteArray {
        rfc822Count += 1
        return unused()
    }

    override suspend fun storeFlags(uids: List<Long>, add: Set<String>, remove: Set<String>) {
        throwIfArmed()
        stores += FlagWrite(uids, add, remove)
    }

    override suspend fun uidExpungeDeleted() {
        throwIfArmed()
        expungeCount += 1
    }

    override suspend fun copyThenDelete(uids: List<Long>, targetMailbox: String) {
        throwIfArmed()
        copies += CopyWrite(uids, targetMailbox)
    }

    override suspend fun searchText(query: String): List<Long> {
        searchCalls += query
        throwIfArmed()
        return searchUids
    }

    override suspend fun searchCriterion(kind: String, argument: String): List<Long> {
        searchCalls += argument
        throwIfArmed()
        return searchUids
    }

    override suspend fun sort(key: SortKey, newestFirst: Boolean): List<Long> {
        sortCalls += key to newestFirst
        throwIfArmed()
        return sortUids
    }

    override suspend fun thread(key: SortKey): ThreadNode {
        threadCalls += key
        throwIfArmed()
        return threadNode
    }

    override suspend fun watch(mailbox: String, onChange: (MailboxChange) -> Unit) {
        watchMailboxes += mailbox
    }

    override suspend fun stopWatch() {
        stopWatchCount += 1
    }

    override suspend fun append(mailbox: String, rfc822: ByteArray, flags: Set<String>) {
        appendCount += 1
    }

    override suspend fun smtpSend(rfc822: ByteArray, recipients: List<String>) = unused()

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
