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
import org.dlang.liveimap.session.SearchEdge
import org.dlang.liveimap.session.SelectResult
import org.dlang.liveimap.session.ThreadNode
import org.dlang.liveimap.settings.AccountSettings
import org.dlang.liveimap.settings.Density
import org.dlang.liveimap.settings.FolderView
import org.dlang.liveimap.settings.SettingsStore
import org.dlang.liveimap.settings.SortKey
import org.dlang.liveimap.settings.StartAfterChange
import org.dlang.liveimap.settings.StartRule
import org.dlang.liveimap.settings.openAtMenuText
import org.dlang.liveimap.settings.startRuleChoices
import org.dlang.liveimap.settings.SwipeAction
import org.dlang.liveimap.settings.SwipeBinding
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
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
        assertEquals(IndexMode.ArrivalRange, request.mode)
        assertEquals("INBOX", request.mailbox)
        assertEquals(IndexPageSize, request.limit)
        assertEquals(IndexPageSize, request.prefetch)
        assertTrue(request.uids.isEmpty())
        assertTrue(!request.includePreview)
        assertTrue(session.sortCalls.isEmpty())
        assertTrue(session.threadCalls.isEmpty())
        assertEquals(listOf(8L, 3L), rows.map { it.uid })
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
        assertEquals(IndexMode.ArrivalRange, session.fetchRequests.single().mode)
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
        assertEquals(listOf(3L, 2L, 1L), model.rows.map { it.uid })
        val searched = runImmediate { model.applySearch("needle") }
        assertEquals(listOf("needle"), session.searchCalls)
        assertEquals(IndexMode.ByUid, session.fetchRequests.last().mode)
        assertEquals(listOf(9L, 8L), session.fetchRequests.last().uids)
        assertEquals(listOf(9L, 8L), searched.map { it.uid })
        runImmediate { model.applySearch("") }
        assertEquals(listOf("needle"), session.searchCalls)
        assertEquals(listOf(3L, 2L, 1L), model.rows.map { it.uid })
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
        session.exists = 6
        session.rows[8L] = row(8L, sequence = 5)
        session.rows[9L] = row(9L, sequence = 6)
        runImmediate { model.applyChange(MailboxChange.Exists(6)) }
        assertEquals(4, model.rows.first { it.uid == 7L }.sequence)
        assertEquals(IndexMode.ArrivalNewest, session.fetchRequests.last().mode)
    }

    @Test
    fun pagingDropsThePageThatLeft() {
        val session = FakeMailSession()
        val uids = (130L downTo 1L).toList()
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
        val left = uids.take(IndexPageSize).toSet()
        assertTrue(model.rows.none { it.uid in left })
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
        assertEquals(listOf(6L, 5L), OpenMessageOrder.uids)
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

    @Test
    fun threadMessageOrderInsertsExpandedHiddenUids() {
        val roots = listOf(3L, 5L, 9L)
        val hidden = mapOf(5L to listOf(1L), 9L to listOf(4L, 2L))
        assertEquals(listOf(3L, 5L, 9L), threadMessageOrder(roots, hidden, emptySet()))
        assertEquals(listOf(3L, 5L, 1L, 9L), threadMessageOrder(roots, hidden, setOf(5L)))
        assertEquals(listOf(3L, 5L, 9L, 4L, 2L), threadMessageOrder(roots, hidden, setOf(9L)))
    }

    @Test
    fun arrivalNewestFirstRowZeroIsHighestSequence() {
        val session = folder(300)
        val model = IndexModel(session, MemorySettingsStore(AccountSettings()), "INBOX")
        val rows = runImmediate { model.loadWindow() }
        val request = session.fetchRequests.single()
        assertEquals(IndexMode.ArrivalRange, request.mode)
        assertEquals(181, request.firstSequence)
        assertEquals(300, request.lastSequence)
        assertEquals(300, rows.first().sequence)
        assertEquals(181, rows.last().sequence)
        assertEquals(0, model.startIndex)
    }

    @Test
    fun arrivalOldestFirstOpensAtNewestEnd() {
        val session = folder(300)
        val store = MemorySettingsStore(
            AccountSettings(defaultView = FolderView(SortKey.Arrival, newestFirst = false)),
        )
        val model = IndexModel(session, store, "INBOX")
        val rows = runImmediate { model.loadWindow() }
        assertEquals(181, rows.first().sequence)
        assertEquals(300, rows.last().sequence)
        assertTrue(rows.zipWithNext().all { (left, right) -> left.sequence < right.sequence })
        assertEquals(rows.lastIndex, model.startIndex)
    }

    @Test
    fun arrivalPagesPastFirst120() {
        val session = folder(300)
        val model = IndexModel(session, MemorySettingsStore(AccountSettings()), "INBOX")
        val first = runImmediate { model.loadWindow() }
        assertTrue(first.none { it.sequence < 181 })
        var guard = 0
        while (model.rows.none { it.sequence == 1 } && guard < 10) {
            runImmediate { model.onFirstVisible(0) }
            runImmediate { model.onFirstVisible(IndexPageSize) }
            guard += 1
        }
        assertTrue(model.rows.any { it.sequence < 181 })
        assertTrue(model.rows.any { it.sequence == 1 })
    }

    @Test
    fun arrivalFilterSearchesWholeFolder() {
        val session = folder(300)
        session.searchUids = listOf(4L, 20L, 290L)
        val model = IndexModel(session, MemorySettingsStore(AccountSettings()), "INBOX")
        val rows = runImmediate { model.applyCriterion("From", "ada", narrow = false, label = "From") }
        assertTrue(rows.any { it.uid == 4L })
        assertTrue(rows.any { it.uid == 20L })
        assertTrue(rows.any { it.uid == 290L })
        assertTrue(session.fetchRequests.none { it.mode == IndexMode.ArrivalNewest || it.mode == IndexMode.ArrivalOldest })
    }

    @Test
    fun newMailAtNewestEndInsertsWithoutReload() {
        val newestSession = folder(10)
        val newest = IndexModel(newestSession, MemorySettingsStore(AccountSettings()), "INBOX")
        runImmediate { newest.loadWindow() }
        val newestFetches = newestSession.fetchRequests.size
        newestSession.exists = 12
        newestSession.rows[11L] = row(11L)
        newestSession.rows[12L] = row(12L)
        runImmediate { newest.applyChange(MailboxChange.Exists(12)) }
        assertEquals(newestFetches + 1, newestSession.fetchRequests.size)
        assertEquals(IndexMode.ArrivalNewest, newestSession.fetchRequests.last().mode)
        assertEquals(2, newestSession.fetchRequests.last().limit)
        assertEquals((12L downTo 1L).toList(), newest.rows.map { it.uid })
        assertEquals(0, newest.pendingNew)

        val oldestSession = folder(10)
        val oldest = IndexModel(
            oldestSession,
            MemorySettingsStore(AccountSettings(defaultView = FolderView(SortKey.Arrival, newestFirst = false))),
            "INBOX",
        )
        runImmediate { oldest.loadWindow() }
        val oldestFetches = oldestSession.fetchRequests.size
        oldestSession.exists = 12
        oldestSession.rows[11L] = row(11L)
        oldestSession.rows[12L] = row(12L)
        runImmediate { oldest.applyChange(MailboxChange.Exists(12)) }
        assertEquals(oldestFetches + 1, oldestSession.fetchRequests.size)
        assertEquals(IndexMode.ArrivalNewest, oldestSession.fetchRequests.last().mode)
        assertEquals((1L..12L).toList(), oldest.rows.map { it.uid })
        assertEquals(0, oldest.pendingNew)
    }

    @Test
    fun newMailAwayFromNewestEndKeepsWindow() {
        val session = folder(300)
        val model = IndexModel(session, MemorySettingsStore(AccountSettings()), "INBOX")
        runImmediate { model.loadWindow() }
        runImmediate { model.onFirstVisible(0) }
        runImmediate { model.onFirstVisible(IndexPageSize) }
        assertTrue(model.anchorPage > 0)
        val before = model.rows.map { it.uid }
        val fetches = session.fetchRequests.size
        session.exists = 302
        session.rows[301L] = row(301L)
        session.rows[302L] = row(302L)
        runImmediate { model.applyChange(MailboxChange.Exists(302)) }
        assertEquals(before, model.rows.map { it.uid })
        assertEquals(2, model.pendingNew)
        assertEquals(fetches, session.fetchRequests.size)
    }

    @Test
    fun searchFollowsDirection() {
        val session = FakeMailSession()
        session.searchUids = listOf(1L, 5L, 3L)
        listOf(1L, 3L, 5L).forEach { session.rows[it] = row(it) }
        val newest = IndexModel(
            session,
            MemorySettingsStore(AccountSettings(defaultView = FolderView(SortKey.Arrival, newestFirst = true))),
            "INBOX",
        )
        runImmediate { newest.loadWindow() }
        val newestRows = runImmediate { newest.applySearch("a") }
        assertEquals(listOf(5L, 3L, 1L), newestRows.map { it.uid })
        assertEquals(0, newest.startIndex)

        val oldestSession = FakeMailSession()
        oldestSession.searchUids = listOf(1L, 5L, 3L)
        listOf(1L, 3L, 5L).forEach { oldestSession.rows[it] = row(it) }
        val oldest = IndexModel(
            oldestSession,
            MemorySettingsStore(AccountSettings(defaultView = FolderView(SortKey.Arrival, newestFirst = false))),
            "INBOX",
        )
        runImmediate { oldest.loadWindow() }
        val oldestRows = runImmediate { oldest.applySearch("a") }
        assertEquals(listOf(1L, 3L, 5L), oldestRows.map { it.uid })
        assertEquals(oldestRows.lastIndex, oldest.startIndex)
    }

    @Test
    fun newestAtEndRuleTable() {
        val timeOrdered = setOf(
            SortKey.Arrival,
            SortKey.Date,
            SortKey.ThreadReferences,
            SortKey.ThreadOrderedSubject,
        )
        for (key in SortKey.entries) {
            for (newestFirst in listOf(true, false)) {
                val session = FakeMailSession()
                when (key) {
                    SortKey.Arrival -> {
                        session.exists = 3
                        session.arrivalRows = listOf(row(1L), row(2L), row(3L))
                    }
                    SortKey.ThreadReferences, SortKey.ThreadOrderedSubject -> {
                        session.threadNode = ThreadNode(
                            uid = null,
                            children = listOf(
                                ThreadNode(1L, emptyList()),
                                ThreadNode(2L, emptyList()),
                                ThreadNode(3L, emptyList()),
                            ),
                        )
                        listOf(1L, 2L, 3L).forEach { session.rows[it] = row(it) }
                    }
                    else -> {
                        val ordered = if (!newestFirst && key in timeOrdered) {
                            listOf(1L, 2L, 3L)
                        } else {
                            listOf(3L, 2L, 1L)
                        }
                        session.sortUids = ordered
                        ordered.forEach { session.rows[it] = row(it) }
                    }
                }
                val model = IndexModel(
                    session,
                    MemorySettingsStore(AccountSettings(defaultView = FolderView(key, newestFirst))),
                    "INBOX",
                )
                val loaded = runImmediate { model.loadWindow() }
                assertEquals(!newestFirst, newestAtEnd(model.view))
                if (!newestFirst && key in timeOrdered) {
                    assertEquals(loaded.lastIndex, model.startIndex)
                } else {
                    assertEquals(0, model.startIndex)
                }
                assertTrue(loaded.isNotEmpty())
            }
        }
    }

    @Test
    fun startFirstUnseenArrivalNewestFirst() {
        val session = folder(300)
        session.capabilities = setOf("ESEARCH")
        session.unseenSeq.addAll(listOf(150, 151, 290))
        val model = model(session, StartRule.FirstUnseen, newestFirst = true)
        val rows = runImmediate { model.loadWindow() }
        assertEquals(290, rows[model.startIndex].sequence)
        assertEquals(1, session.startSearches.size)
        val search = session.startSearches.single()
        assertEquals(StartRule.FirstUnseen.name, search.rule)
        assertEquals(false, search.byUid)
        assertEquals(SearchEdge.Max.name, search.edge)
        assertEquals("UNDELETED UNSEEN", search.key)
        val fetch = session.fetchRequests.single()
        assertEquals(IndexMode.ArrivalRange, fetch.mode)
        assertTrue(fetch.lastSequence - fetch.firstSequence + 1 <= 120)
        assertTrue(290 in fetch.firstSequence..fetch.lastSequence)
    }

    @Test
    fun startFirstUnseenArrivalOldestFirst() {
        val session = folder(300)
        session.unseenSeq.addAll(listOf(150, 151, 290))
        val model = model(session, StartRule.FirstUnseen, newestFirst = false)
        val rows = runImmediate { model.loadWindow() }
        assertEquals(150, rows[model.startIndex].sequence)
        assertTrue(session.fetchRequests.none { it.firstSequence == 1 && 1 !in it.firstSequence..it.lastSequence })
        assertTrue(session.fetchRequests.none { it.firstSequence == 1 })
    }

    @Test
    fun startNoMatchFallsBackToNewest() {
        val newestSession = folder(300)
        val newest = model(newestSession, StartRule.FirstUnseen, newestFirst = true)
        val newestRows = runImmediate { newest.loadWindow() }
        assertEquals(300, newestRows[newest.startIndex].sequence)
        assertEquals(1, newestSession.startSearches.size)
        val oldestSession = folder(300)
        val oldest = model(oldestSession, StartRule.FirstUnseen, newestFirst = false)
        val oldestRows = runImmediate { oldest.loadWindow() }
        assertEquals(300, oldestRows[oldest.startIndex].sequence)
        assertEquals(1, oldestSession.startSearches.size)
    }

    @Test
    fun startSkipsDeleted() {
        val session = folder(300)
        session.unseenSeq.addAll(listOf(150, 151))
        session.deletedSeq.add(150)
        val model = model(session, StartRule.FirstUnseen, newestFirst = false)
        val rows = runImmediate { model.loadWindow() }
        assertEquals(151, rows[model.startIndex].sequence)
    }

    @Test
    fun startFirstRecentUsesNew() {
        val session = folder(80)
        session.unseenSeq.addAll(listOf(10, 40))
        session.recentSeq.add(40)
        val model = model(session, StartRule.FirstRecent, newestFirst = false)
        val rows = runImmediate { model.loadWindow() }
        assertEquals("UNDELETED NEW", session.startSearches.single().key)
        assertEquals(40, rows[model.startIndex].sequence)
    }

    @Test
    fun startImportantOrUnseenTakesEarlier() {
        val session = folder(300)
        session.flaggedSeq.add(100)
        session.unseenSeq.add(150)
        val model = model(session, StartRule.FirstImportantOrUnseen, newestFirst = false)
        val rows = runImmediate { model.loadWindow() }
        assertEquals(100, rows[model.startIndex].sequence)
        assertEquals("UNDELETED OR FLAGGED UNSEEN", session.startSearches.single().key)
    }

    @Test
    fun startSortedFromFirstImportant() {
        val session = FakeMailSession(capabilities = setOf("ESEARCH"))
        val uids = (1L..200L).toList()
        session.sortUids = uids
        uids.forEach { session.rows[it] = row(it) }
        session.flaggedUids.add(uids[75])
        val store = MemorySettingsStore(
            AccountSettings(
                inboxStart = StartRule.FirstImportant,
                defaultView = FolderView(SortKey.From, newestFirst = true),
            ),
        )
        val model = IndexModel(session, store, "INBOX")
        val rows = runImmediate { model.loadWindow() }
        assertEquals(75, model.order.indexOf(rows[model.startIndex].uid))
        assertTrue(model.anchorPage == 0 || model.anchorPage == 1)
        assertTrue(rows.any { it.uid == uids[75] })
        val window = model.order.drop(model.anchorPage * IndexPageSize).take(IndexPageSize * 2).toSet()
        assertTrue(session.fetchRequests.all { request ->
            request.mode != IndexMode.ByUid || request.uids.all { it in window }
        })
        val search = session.startSearches.single()
        assertEquals(true, search.byUid)
        assertEquals(SearchEdge.All.name, search.edge)
        assertEquals("UNDELETED FLAGGED", search.key)
    }

    @Test
    fun startThreadHiddenMemberUnseen() {
        val session = FakeMailSession()
        session.threadNode = ThreadNode(
            uid = null,
            children = listOf(
                ThreadNode(5L, listOf(ThreadNode(1L, emptyList()))),
                ThreadNode(3L, emptyList()),
            ),
        )
        listOf(5L, 1L, 3L).forEach { session.rows[it] = row(it) }
        session.unseenUids.add(1L)
        val store = MemorySettingsStore(
            AccountSettings(
                inboxStart = StartRule.FirstUnseen,
                defaultView = FolderView(SortKey.ThreadReferences, newestFirst = true),
            ),
        )
        val model = IndexModel(session, store, "INBOX")
        val rows = runImmediate { model.loadWindow() }
        assertEquals(5L, rows[model.startIndex].uid)
        assertEquals(listOf(5L, 3L), rows.map { it.uid })
        assertEquals(listOf(1L), model.threadHidden[5L])
        assertEquals(true, session.startSearches.single().byUid)
    }

    @Test
    fun startFilterIntersects() {
        val session = folder(10)
        session.unseenSeq.addAll(listOf(2, 8))
        session.searchUids = listOf(2L, 3L, 4L)
        val model = model(session, StartRule.FirstUnseen, newestFirst = true)
        val rows = runImmediate { model.applyCriterion("From", "ada", narrow = false, label = "From") }
        assertEquals(2L, rows[model.startIndex].uid)
        assertTrue(rows.none { it.uid == 8L })
        assertEquals(true, session.startSearches.single().byUid)
    }

    @Test
    fun startFirstLastAreTopBottom() {
        for (newestFirst in listOf(true, false)) {
            for (esearch in listOf(false, true)) {
                for (rule in listOf(StartRule.First, StartRule.Last)) {
                    val session = folder(300)
                    if (esearch) session.capabilities = setOf("ESEARCH")
                    val opened = model(session, rule, newestFirst)
                    val rows = runImmediate { opened.loadWindow() }
                    val sequence = rows[opened.startIndex].sequence
                    val top = if (newestFirst) 300 else 1
                    val bottom = if (newestFirst) 1 else 300
                    assertEquals(if (rule == StartRule.First) top else bottom, sequence)
                    if (!esearch) {
                        assertTrue(session.startSearches.isEmpty())
                    } else {
                        assertEquals("UNDELETED", session.startSearches.single().key)
                        assertEquals(1, session.startSearches.size)
                    }
                }
            }
        }
        val sorted = FakeMailSession(capabilities = setOf("ESEARCH"))
        sorted.sortUids = listOf(1L, 2L, 3L)
        listOf(1L, 2L, 3L).forEach { sorted.rows[it] = row(it) }
        val store = MemorySettingsStore(
            AccountSettings(
                inboxStart = StartRule.Last,
                defaultView = FolderView(SortKey.From, newestFirst = true),
            ),
        )
        val model = IndexModel(sorted, store, "INBOX")
        val rows = runImmediate { model.loadWindow() }
        assertEquals(3L, rows[model.startIndex].uid)
        assertTrue(sorted.startSearches.isEmpty())
    }

    @Test
    fun startSearchFailureFallsBackToNewest() {
        val session = folder(300)
        session.startFailure = MailFailure("search failed")
        session.unseenSeq.add(10)
        val model = model(session, StartRule.FirstUnseen, newestFirst = true)
        val rows = runImmediate { model.loadWindow() }
        assertEquals("search failed", model.notice)
        assertEquals(300, rows[model.startIndex].sequence)
        assertEquals(1, session.startSearches.size)
    }

    @Test
    fun startNotCached() {
        val session = folder(30)
        session.unseenSeq.addAll(listOf(10, 20))
        val model = model(session, StartRule.FirstUnseen, newestFirst = true)
        val first = runImmediate { model.loadWindow() }
        assertEquals(20, first[model.startIndex].sequence)
        runImmediate { model.changeFlags(listOf(20L), setOf("\\Seen"), emptySet()) }
        val second = runImmediate { model.loadWindow() }
        assertEquals(2, session.startSearches.size)
        assertEquals(10, second[model.startIndex].sequence)
    }

    @Test
    fun firstVisibleAfterStartDoesNotPage() {
        val session = folder(300)
        session.capabilities = setOf("ESEARCH")
        session.unseenSeq.add(240)
        val model = model(session, StartRule.FirstUnseen, newestFirst = true)
        runImmediate { model.loadWindow() }
        assertEquals(0, model.anchorPage)
        assertEquals(60, model.startIndex)
        val anchor = model.anchorPage
        runImmediate { model.onFirstVisible(model.startIndex) }
        assertEquals(anchor, model.anchorPage)
    }

    @Test
    fun keepTopVisibleOnSort() {
        val keepSession = folder(10)
        keepSession.sortUids = (1L..10L).toList()
        val keepStore = MemorySettingsStore(
            AccountSettings(startAfterChange = StartAfterChange.KeepTopVisible),
        )
        val keep = IndexModel(keepSession, keepStore, "INBOX")
        runImmediate { keep.loadWindow() }
        runImmediate { keep.onFirstVisible(3) }
        val top = keep.rows[3].uid
        runImmediate { keep.applyView(FolderView(SortKey.From, newestFirst = true)) }
        assertEquals(top, keep.rows[keep.startIndex].uid)

        val rerunSession = folder(10)
        rerunSession.sortUids = (1L..10L).toList()
        val rerun = IndexModel(rerunSession, MemorySettingsStore(AccountSettings()), "INBOX")
        runImmediate { rerun.loadWindow() }
        runImmediate { rerun.onFirstVisible(3) }
        runImmediate { rerun.applyView(FolderView(SortKey.From, newestFirst = true)) }
        assertEquals(10L, rerun.rows[rerun.startIndex].uid)
    }

    @Test
    fun keepTopVisibleFilteredOut() {
        val session = folder(10)
        session.unseenSeq.add(2)
        session.searchUids = listOf(2L, 3L)
        val store = MemorySettingsStore(
            AccountSettings(
                inboxStart = StartRule.FirstUnseen,
                startAfterChange = StartAfterChange.KeepTopVisible,
            ),
        )
        val model = IndexModel(session, store, "INBOX")
        runImmediate { model.loadWindow() }
        runImmediate { model.onFirstVisible(0) }
        val rows = runImmediate { model.applyCriterion("From", "ada", narrow = false, label = "From") }
        assertEquals(2L, rows[model.startIndex].uid)
        assertTrue(rows.none { it.uid == 10L })
    }

    @Test
    fun recentRulesHidden() {
        val hidden = startRuleChoices(false, StartRule.Newest)
        assertFalse(hidden.contains(StartRule.FirstRecent))
        assertFalse(hidden.contains(StartRule.FirstImportantOrRecent))
        val kept = startRuleChoices(false, StartRule.FirstRecent)
        assertTrue(kept.contains(StartRule.FirstRecent))
        val session = folder(50)
        session.recentSeq.add(12)
        session.unseenSeq.add(12)
        val store = MemorySettingsStore(
            AccountSettings(inboxStart = StartRule.FirstRecent, showRecentRules = false),
        )
        val model = IndexModel(session, store, "INBOX")
        val rows = runImmediate { model.loadWindow() }
        assertEquals(12, rows[model.startIndex].sequence)
        assertEquals("UNDELETED NEW", session.startSearches.single().key)
    }

    @Test
    fun openAtMenuItem() {
        assertNull(openAtMenuText(AccountSettings()))
        assertEquals(
            "Open this folder at\u2026",
            openAtMenuText(AccountSettings(openAtInIndexMenu = true)),
        )
        val session = folder(3)
        val store = MemorySettingsStore(AccountSettings())
        val model = IndexModel(session, store, "INBOX")
        runImmediate { model.setFolderStart(StartRule.First) }
        assertEquals(StartRule.First, store.settings.folderStarts["INBOX"])
        runImmediate { model.setFolderStart(null) }
        assertFalse(store.settings.folderStarts.containsKey("INBOX"))
    }
}

private fun model(session: FakeMailSession, rule: StartRule, newestFirst: Boolean): IndexModel {
    val store = MemorySettingsStore(
        AccountSettings(
            inboxStart = rule,
            defaultView = FolderView(SortKey.Arrival, newestFirst),
        ),
    )
    return IndexModel(session, store, "INBOX")
}

private fun folder(exists: Int): FakeMailSession {
    val session = FakeMailSession()
    session.exists = exists
    for (sequence in 1..exists) {
        session.rows[sequence.toLong()] = row(sequence.toLong())
    }
    return session
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

private data class StartSearch(val rule: String, val byUid: Boolean, val edge: String, val key: String)

private data class CopyWrite(val uids: List<Long>, val target: String)

private class FakeMailSession(
    override var capabilities: Set<String> = emptySet(),
) : MailSession {
    val fetchRequests = mutableListOf<IndexRequest>()
    val sortCalls = mutableListOf<Pair<SortKey, Boolean>>()
    val threadCalls = mutableListOf<SortKey>()
    val searchCalls = mutableListOf<String>()
    val startSearches = mutableListOf<StartSearch>()
    val locateCalls = mutableListOf<Long>()
    val unseenSeq = HashSet<Int>()
    val recentSeq = HashSet<Int>()
    val flaggedSeq = HashSet<Int>()
    val deletedSeq = HashSet<Int>()
    val unseenUids = HashSet<Long>()
    val recentUids = HashSet<Long>()
    val flaggedUids = HashSet<Long>()
    val deletedUids = HashSet<Long>()
    var startFailure: MailFailure? = null
    val stores = mutableListOf<FlagWrite>()
    val copies = mutableListOf<CopyWrite>()
    val rows = HashMap<Long, IndexRow>()
    var arrivalRows: List<IndexRow> = emptyList()
    var exists: Int = -1
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

    override suspend fun selectedExists(): Int {
        if (exists >= 0) return exists
        if (arrivalRows.isNotEmpty()) return arrivalRows.maxOf { it.sequence }
        return rows.values.maxOfOrNull { it.sequence } ?: 0
    }

    override suspend fun fetchIndex(request: IndexRequest): List<IndexRow> {
        fetchRequests += request
        throwIfArmed()
        return when (request.mode) {
            IndexMode.ByUid -> request.uids.asReversed().map { uid -> rows[uid] ?: row(uid) }
            IndexMode.ArrivalRange -> {
                val first = request.firstSequence
                val last = request.lastSequence
                val pool = if (arrivalRows.isNotEmpty()) arrivalRows else rows.values.toList()
                if (first <= 0 || last <= 0 || first > last) {
                    emptyList()
                } else {
                    pool.filter { it.sequence in first..last }
                }
            }
            IndexMode.ArrivalNewest -> {
                val pool = sequencePool().sortedByDescending { it.sequence }
                if (request.limit in 1 until pool.size) {
                    pool.take(request.limit)
                } else if (arrivalRows.isNotEmpty()) {
                    arrivalRows
                } else {
                    pool
                }
            }
            IndexMode.ArrivalOldest -> arrivalRows
        }
    }

    private fun sequencePool(): List<IndexRow> {
        val bySequence = LinkedHashMap<Int, IndexRow>()
        for (row in arrivalRows) bySequence[row.sequence] = row
        for (row in rows.values) {
            if (row.sequence !in bySequence) bySequence[row.sequence] = row
        }
        return bySequence.values.toList()
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
        for (uid in uids) {
            val current = rows[uid] ?: continue
            rows[uid] = applyFlagEdit(current, add, remove)
        }
    }

    private fun applyFlagEdit(row: IndexRow, add: Set<String>, remove: Set<String>): IndexRow {
        fun mark(seq: MutableSet<Int>, ids: MutableSet<Long>, on: Boolean) {
            if (on) {
                seq.add(row.sequence)
                ids.add(row.uid)
            } else {
                seq.remove(row.sequence)
                ids.remove(row.uid)
            }
        }
        if ("\\Seen" in add) mark(unseenSeq, unseenUids, false)
        if ("\\Seen" in remove) mark(unseenSeq, unseenUids, true)
        if ("\\Recent" in add) mark(recentSeq, recentUids, true)
        if ("\\Recent" in remove) mark(recentSeq, recentUids, false)
        if ("\\Flagged" in add) mark(flaggedSeq, flaggedUids, true)
        if ("\\Flagged" in remove) mark(flaggedSeq, flaggedUids, false)
        if ("\\Deleted" in add) mark(deletedSeq, deletedUids, true)
        if ("\\Deleted" in remove) mark(deletedSeq, deletedUids, false)
        return row.copy(flags = (row.flags + add) - remove)
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

    override suspend fun searchStart(rule: StartRule, byUid: Boolean, edge: SearchEdge): List<Long> {
        val key = startKey(rule)
        startSearches += StartSearch(rule.name, byUid, edge.name, key)
        val pending = startFailure
        if (pending != null) {
            startFailure = null
            throw pending
        }
        throwIfArmed()
        val matched = sequencePool().filter { rowMatches(rule, it) }
        val numbers = if (byUid) matched.map { it.uid } else matched.map { it.sequence.toLong() }
        return when (edge) {
            SearchEdge.Min -> numbers.minOrNull()?.let { listOf(it) } ?: emptyList()
            SearchEdge.Max -> numbers.maxOrNull()?.let { listOf(it) } ?: emptyList()
            SearchEdge.All -> numbers
        }
    }

    override suspend fun locateUid(uid: Long): List<Long> {
        locateCalls += uid
        val found = rows[uid] ?: arrivalRows.firstOrNull { it.uid == uid }
        return if (found == null) emptyList() else listOf(found.sequence.toLong())
    }

    private fun rowMatches(rule: StartRule, row: IndexRow): Boolean {
        if (isMarked(row, deletedSeq, deletedUids, "\\Deleted")) return false
        val unseen = isMarked(row, unseenSeq, unseenUids, "\\Unseen")
        val recent = isMarked(row, recentSeq, recentUids, "\\Recent")
        val flagged = isMarked(row, flaggedSeq, flaggedUids, "\\Flagged")
        return when (rule) {
            StartRule.FirstUnseen -> unseen
            StartRule.FirstRecent -> recent && unseen
            StartRule.FirstImportant -> flagged
            StartRule.FirstImportantOrUnseen -> flagged || unseen
            StartRule.FirstImportantOrRecent -> flagged || (recent && unseen)
            StartRule.First, StartRule.Last -> true
            StartRule.Newest -> false
        }
    }

    private fun isMarked(row: IndexRow, seq: Set<Int>, ids: Set<Long>, flag: String): Boolean =
        row.sequence in seq || row.uid in ids || flag in row.flags

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

private fun startKey(rule: StartRule): String = when (rule) {
    StartRule.FirstUnseen -> "UNDELETED UNSEEN"
    StartRule.FirstRecent -> "UNDELETED NEW"
    StartRule.FirstImportant -> "UNDELETED FLAGGED"
    StartRule.FirstImportantOrUnseen -> "UNDELETED OR FLAGGED UNSEEN"
    StartRule.FirstImportantOrRecent -> "UNDELETED OR FLAGGED NEW"
    StartRule.First, StartRule.Last -> "UNDELETED"
    StartRule.Newest -> ""
}

private fun <T> runImmediate(block: suspend () -> T): T {
    var result: Result<T>? = null
    block.startCoroutine(Continuation(EmptyCoroutineContext) { result = it })
    return result!!.getOrThrow()
}
