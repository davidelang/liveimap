package org.dlang.liveimap.ui.index

import org.dlang.liveimap.engine.searchNeedsCharset
import org.dlang.liveimap.session.ComposeKind
import org.dlang.liveimap.session.ComposeSeed
import org.dlang.liveimap.session.FolderEntry
import org.dlang.liveimap.session.IndexMode
import org.dlang.liveimap.session.IndexRequest
import org.dlang.liveimap.session.IndexRow
import org.dlang.liveimap.session.MailFailure
import org.dlang.liveimap.session.MailSession
import org.dlang.liveimap.session.MailboxChange
import org.dlang.liveimap.session.MailboxUids
import org.dlang.liveimap.session.MimePart
import org.dlang.liveimap.session.Namespace
import org.dlang.liveimap.session.NamespaceKind
import org.dlang.liveimap.session.OpenResult
import org.dlang.liveimap.session.SearchEdge
import org.dlang.liveimap.session.SelectResult
import org.dlang.liveimap.session.ThreadNode
import org.dlang.liveimap.settings.AccountSettings
import org.dlang.liveimap.settings.DateFormat
import org.dlang.liveimap.settings.DeletePolicy
import org.dlang.liveimap.settings.Density
import org.dlang.liveimap.settings.MoveMethod
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
        assertEquals(original.folderViews, store.settings.folderViews)
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
    fun orderedSubjectStaysListedWhenNotAdvertised() {
        val references = FakeMailSession(capabilities = setOf("THREAD=REFERENCES"))
        val hidden = IndexModel(references, MemorySettingsStore(AccountSettings()), "INBOX")
        assertEquals(SortKey.entries, hidden.menuKeys)
        assertFalse(sortKeyAdvertised(references.featureCaps, SortKey.ThreadOrderedSubject))
        assertTrue(sortKeyAdvertised(references.featureCaps, SortKey.ThreadReferences))
        assertTrue(SortKey.ThreadOrderedSubject in hidden.menuKeys)
        val ordered = FakeMailSession(capabilities = setOf("thread=orderedsubject"))
        val shown = IndexModel(ordered, MemorySettingsStore(AccountSettings()), "INBOX")
        assertTrue(sortKeyAdvertised(ordered.featureCaps, SortKey.ThreadOrderedSubject))
        assertEquals(SortKey.entries, shown.menuKeys)
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
    fun effectiveDeletePolicyFallbacks() {
        assertEquals(
            DeletePolicy.DeletePermanently,
            effectiveDeletePolicy(DeletePolicy.MarkDeleted, inTrash = true, uidPlus = true, trashKnown = true),
        )
        assertEquals(
            DeletePolicy.MarkDeleted,
            effectiveDeletePolicy(DeletePolicy.DeletePermanently, inTrash = true, uidPlus = false, trashKnown = true),
        )
        assertEquals(
            DeletePolicy.MarkDeleted,
            effectiveDeletePolicy(DeletePolicy.DeletePermanently, inTrash = false, uidPlus = false, trashKnown = false),
        )
        assertEquals(
            DeletePolicy.MarkDeleted,
            effectiveDeletePolicy(DeletePolicy.MoveToTrash, inTrash = false, uidPlus = true, trashKnown = false),
        )
        assertEquals(
            DeletePolicy.MoveToTrash,
            effectiveDeletePolicy(DeletePolicy.MoveToTrash, inTrash = false, uidPlus = false, trashKnown = true),
        )
        assertEquals(
            DeletePolicy.DeletePermanently,
            effectiveDeletePolicy(DeletePolicy.DeletePermanently, inTrash = false, uidPlus = true, trashKnown = false),
        )
    }

    @Test
    fun alternatePoliciesOmitTheEffectiveAndTheImpossible() {
        assertEquals(
            emptyList<DeletePolicy>(),
            alternateDeletePolicies(DeletePolicy.MarkDeleted, uidPlus = false, trashKnown = false),
        )
        assertEquals(
            listOf(DeletePolicy.MoveToTrash),
            alternateDeletePolicies(DeletePolicy.MarkDeleted, uidPlus = false, trashKnown = true),
        )
        assertEquals(
            listOf(DeletePolicy.MarkDeleted, DeletePolicy.MoveToTrash),
            alternateDeletePolicies(DeletePolicy.DeletePermanently, uidPlus = true, trashKnown = true),
        )
    }

    @Test
    fun defaultMoveStillCopiesWhenMoveIsAdvertised() {
        val session = FakeMailSession(capabilities = setOf("MOVE"))
        session.arrivalRows = listOf(row(1))
        val model = IndexModel(session, MemorySettingsStore(AccountSettings()), "INBOX")
        runImmediate { model.loadWindow() }
        runImmediate { model.moveMessages(listOf(1L), "Archive") }
        assertEquals(listOf(CopyWrite(listOf(1L), "Archive")), session.copies)
        assertEquals(false, model.mailUndo?.usedMove)
    }

    @Test
    fun imapMoveSetsUsedMoveOnlyWhenAdvertised() {
        val advertised = FakeMailSession(capabilities = setOf("MOVE"))
        advertised.arrivalRows = listOf(row(1))
        val chosen = MemorySettingsStore(AccountSettings(moveMethod = MoveMethod.ImapMove))
        val model = IndexModel(advertised, chosen, "INBOX")
        runImmediate { model.loadWindow() }
        runImmediate { model.moveMessages(listOf(1L), "Archive") }
        assertEquals(listOf(CopyWrite(listOf(1L), "Archive")), advertised.copies)
        assertEquals(true, model.mailUndo?.usedMove)
        val silent = FakeMailSession()
        silent.arrivalRows = listOf(row(1))
        val fallback = IndexModel(silent, MemorySettingsStore(AccountSettings(moveMethod = MoveMethod.ImapMove)), "INBOX")
        runImmediate { fallback.loadWindow() }
        runImmediate { fallback.moveMessages(listOf(1L), "Archive") }
        assertEquals(listOf(CopyWrite(listOf(1L), "Archive")), silent.copies)
        assertEquals(false, fallback.mailUndo?.usedMove)
    }

    @Test
    fun swipeDeleteFollowsTrashAndPermanentPolicy() {
        val trash = FakeMailSession()
        trash.knownTrashName = "Trash"
        trash.arrivalRows = listOf(row(6))
        val moveModel = IndexModel(
            trash,
            MemorySettingsStore(AccountSettings(deletePolicy = DeletePolicy.MoveToTrash)),
            "INBOX",
        )
        runImmediate { moveModel.loadWindow() }
        runImmediate { moveModel.performSwipe(6L, SwipeBinding(SwipeAction.Delete)) }
        assertEquals(listOf(CopyWrite(listOf(6L), "Trash")), trash.copies)
        assertTrue(trash.stores.isEmpty())
        assertTrue(trash.uidExpunges.isEmpty())

        val missing = FakeMailSession()
        missing.arrivalRows = listOf(row(6))
        val missingModel = IndexModel(
            missing,
            MemorySettingsStore(AccountSettings(deletePolicy = DeletePolicy.MoveToTrash)),
            "INBOX",
        )
        runImmediate { missingModel.loadWindow() }
        runImmediate { missingModel.performSwipe(6L, SwipeBinding(SwipeAction.Delete)) }
        assertEquals(listOf(FlagWrite(listOf(6L), setOf("\\Deleted"), emptySet())), missing.stores)
        assertTrue(missing.copies.isEmpty())

        val permanent = FakeMailSession(capabilities = setOf("UIDPLUS"))
        permanent.arrivalRows = listOf(row(6, flags = setOf("\\Seen")))
        val permanentModel = IndexModel(
            permanent,
            MemorySettingsStore(AccountSettings(deletePolicy = DeletePolicy.DeletePermanently)),
            "INBOX",
        )
        runImmediate { permanentModel.loadWindow() }
        runImmediate { permanentModel.performSwipe(6L, SwipeBinding(SwipeAction.Delete)) }
        assertEquals(listOf(FlagWrite(listOf(6L), setOf("\\Deleted"), emptySet())), permanent.stores)
        assertEquals(listOf(listOf(6L)), permanent.uidExpunges)
        assertEquals(0, permanent.expungeCount)
        assertNull(permanentModel.mailUndo)
        assertTrue(permanentModel.rows.isEmpty())

        val noPlus = FakeMailSession()
        noPlus.arrivalRows = listOf(row(6))
        val noPlusModel = IndexModel(
            noPlus,
            MemorySettingsStore(AccountSettings(deletePolicy = DeletePolicy.DeletePermanently)),
            "INBOX",
        )
        runImmediate { noPlusModel.loadWindow() }
        runImmediate { noPlusModel.performSwipe(6L, SwipeBinding(SwipeAction.Delete)) }
        assertEquals(listOf(FlagWrite(listOf(6L), setOf("\\Deleted"), emptySet())), noPlus.stores)
        assertTrue(noPlus.uidExpunges.isEmpty())
        assertEquals(0, noPlus.expungeCount)

        val inTrash = FakeMailSession(capabilities = setOf("UIDPLUS"))
        inTrash.knownTrashName = "Trash"
        inTrash.arrivalRows = listOf(row(6))
        val inTrashModel = IndexModel(inTrash, MemorySettingsStore(AccountSettings()), "Trash")
        runImmediate { inTrashModel.loadWindow() }
        runImmediate { inTrashModel.performSwipe(6L, SwipeBinding(SwipeAction.Delete)) }
        assertEquals(listOf(listOf(6L)), inTrash.uidExpunges)
        assertNull(inTrashModel.mailUndo)

        val trashNoPlus = FakeMailSession()
        trashNoPlus.knownTrashName = "Trash"
        trashNoPlus.arrivalRows = listOf(row(6))
        val trashNoPlusModel = IndexModel(
            trashNoPlus,
            MemorySettingsStore(AccountSettings(deletePolicy = DeletePolicy.DeletePermanently)),
            "Trash",
        )
        runImmediate { trashNoPlusModel.loadWindow() }
        runImmediate { trashNoPlusModel.performSwipe(6L, SwipeBinding(SwipeAction.Delete)) }
        assertEquals(listOf(FlagWrite(listOf(6L), setOf("\\Deleted"), emptySet())), trashNoPlus.stores)
        assertTrue(trashNoPlus.uidExpunges.isEmpty())
        assertEquals(0, trashNoPlus.expungeCount)
    }

    @Test
    fun deletePermanentlyAllMailboxUsesUidExpungeDeleted() {
        val session = FakeMailSession(capabilities = setOf("UIDPLUS"))
        session.arrivalRows = listOf(row(1), row(2))
        val model = IndexModel(session, MemorySettingsStore(AccountSettings()), "INBOX")
        runImmediate { model.loadWindow() }
        runImmediate { model.deletePermanently(emptyList(), allMailbox = true) }
        assertEquals(listOf(FlagWrite(emptyList(), setOf("\\Deleted"), emptySet())), session.stores)
        assertEquals(1, session.expungeCount)
        assertTrue(session.uidExpunges.isEmpty())
        assertTrue(model.rows.isEmpty())
        assertNull(model.mailUndo)
    }

    @Test
    fun markAllReadStoresSeenOnWholeMailbox() {
        val session = folder(4)
        session.rows[2L] = row(2L, flags = setOf("\\Flagged"))
        session.searchUids = listOf(2L, 4L)
        val model = IndexModel(session, MemorySettingsStore(AccountSettings()), "INBOX")
        runImmediate { model.loadWindow() }
        runImmediate { model.applySearch("needle") }
        assertEquals(listOf(4L, 2L), model.rows.map { it.uid })
        val rows = runImmediate {
            model.changeFlags(emptyList(), setOf("\\Seen"), emptySet(), allMailbox = true)
        }
        assertEquals(listOf(FlagWrite(emptyList(), setOf("\\Seen"), emptySet())), session.stores)
        assertTrue(rows.all { "\\Seen" in it.flags })
        assertEquals(setOf("\\Seen", "\\Flagged"), rows.first { it.uid == 2L }.flags)
        assertEquals(0, session.expungeCount)
        assertTrue(session.uidExpunges.isEmpty())
        assertNull(model.notice)
        session.failure = MailFailure("store failed")
        val kept = runImmediate {
            model.changeFlags(emptyList(), setOf("\\Seen"), emptySet(), allMailbox = true)
        }
        assertEquals("store failed", model.notice)
        assertEquals(listOf(FlagWrite(emptyList(), setOf("\\Seen"), emptySet())), session.stores)
        assertEquals(rows, kept)
        assertEquals(0, session.expungeCount)
        assertTrue(session.uidExpunges.isEmpty())
    }

    @Test
    fun expungeWithoutUidPlusDoesNotSend() {
        val session = FakeMailSession()
        val model = IndexModel(session, MemorySettingsStore(AccountSettings()), "INBOX")
        runImmediate { model.expunge() }
        assertEquals(0, session.expungeCount)
        runImmediate { model.deletePermanently(listOf(4L)) }
        assertTrue(session.stores.isEmpty())
        assertTrue(session.uidExpunges.isEmpty())
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
        assertEquals(listOf("Subject"), session.searchKinds)
        assertTrue(session.textSearches.isEmpty())
        assertEquals(listOf(3L, 2L, 1L), model.rows.map { it.uid })
        runImmediate { model.applySearch("ada", SimpleSearchField.From) }
        assertEquals(listOf("Subject", "From"), session.searchKinds)
        assertEquals(listOf("needle", "ada"), session.searchCalls)
        runImmediate { model.jumpToNewest() }
        assertEquals(listOf("Subject", "From", "From"), session.searchKinds)
        assertEquals(listOf("needle", "ada", "ada"), session.searchCalls)
        assertTrue(session.textSearches.isEmpty())
        assertFalse(searchNeedsCharset("needle"))
        assertTrue(searchNeedsCharset("é"))
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
        assertEquals(1, existsColumnChars(0))
        assertEquals(3, existsColumnChars(999))
        assertEquals(4, existsColumnChars(1000))
    }

    @Test
    fun dateColumnSamplesIgnoreRowDates() {
        val words = DateColumnWords(
            now = "now",
            min = "min",
            hours = "hours",
            days = "days",
            ago = "%1\$d %2\$s ago",
            badPattern = "bad date pattern",
        )
        assertEquals(listOf("2024-12-30 23:59"), dateColumnSamples(DateFormat.Local, "", words))
        val short = dateColumnSamples(DateFormat.Short, "1999-01-01", words)
        assertEquals(25, short.size)
        assertEquals("23:59", short.first())
        assertEquals(12, short.count { it.contains("30") })
        assertEquals(12, short.count { it.contains("2024") })
        assertFalse(short.contains("1999-01-01"))
        val relative = dateColumnSamples(DateFormat.Relative, "", words)
        assertTrue(relative.contains("now"))
        assertTrue(relative.contains("59 min ago"))
        assertTrue(relative.contains("23 hours ago"))
        assertTrue(relative.contains("6 days ago"))
        assertTrue(relative.containsAll(short))
        assertEquals(
            listOf("2024", "bad date pattern"),
            dateColumnSamples(DateFormat.Custom, "yyyy", words),
        )
        assertEquals(
            listOf("bad date pattern", "bad date pattern"),
            dateColumnSamples(DateFormat.Custom, "", words),
        )
        assertEquals(
            dateColumnSamples(DateFormat.Short, "", words),
            dateColumnSamples(DateFormat.Short, "yyyy-MM-dd", words),
        )
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
        val empty = "No messages"
        val format = "No messages match \u201c%1\$s\u201d in %2\$s"
        assertEquals("No messages", emptyIndexText("", "INBOX", empty, format))
        assertEquals(
            "No messages match \u201cclamp\u201d in INBOX",
            emptyIndexText("clamp", "INBOX", empty, format),
        )
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
        assertEquals(2, newest.pendingNew)
        assertFalse(newest.newMailUnnumbered)

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
        assertEquals(2, oldest.pendingNew)
        assertFalse(oldest.newMailUnnumbered)
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
        assertFalse(model.newMailUnnumbered)
    }

    @Test
    fun newMailInSortedViewDoesNotReload() {
        val session = folder(10)
        session.sortUids = (1L..10L).toList()
        val model = IndexModel(session, MemorySettingsStore(AccountSettings()), "INBOX")
        runImmediate { model.applyView(FolderView(SortKey.From, newestFirst = true)) }
        val fetches = session.fetchRequests.size
        val sorts = session.sortCalls.size
        val before = model.rows.map { it.uid }
        session.exists = 11
        session.rows[11L] = row(11L)
        runImmediate { model.applyChange(MailboxChange.Exists(11)) }
        assertEquals(fetches, session.fetchRequests.size)
        assertEquals(sorts, session.sortCalls.size)
        assertTrue(session.searchCalls.isEmpty())
        assertEquals(before, model.rows.map { it.uid })
        assertEquals(0, model.pendingNew)
        assertTrue(model.newMailUnnumbered)
    }

    @Test
    fun newMailWhileSearchingDoesNotReload() {
        val session = folder(10)
        session.searchUids = listOf(2L, 4L, 6L)
        val model = IndexModel(session, MemorySettingsStore(AccountSettings()), "INBOX")
        runImmediate { model.loadWindow() }
        runImmediate { model.applySearch("needle") }
        val fetches = session.fetchRequests.size
        val searches = session.searchCalls.toList()
        val before = model.rows.map { it.uid }
        session.exists = 12
        session.rows[11L] = row(11L)
        session.rows[12L] = row(12L)
        runImmediate { model.applyChange(MailboxChange.Exists(12)) }
        assertEquals(fetches, session.fetchRequests.size)
        assertEquals(searches, session.searchCalls)
        assertEquals(before, model.rows.map { it.uid })
        assertEquals(0, model.pendingNew)
        assertTrue(model.newMailUnnumbered)
    }

    @Test
    fun flagAndExpungeDoNotRaiseNewMail() {
        val session = folder(10)
        val model = IndexModel(session, MemorySettingsStore(AccountSettings()), "INBOX")
        runImmediate { model.loadWindow() }
        runImmediate { model.applyChange(MailboxChange.Flags(1L, setOf("\\Seen"))) }
        assertEquals(0, model.pendingNew)
        assertFalse(model.newMailUnnumbered)
        assertEquals(setOf("\\Seen"), model.rows.first { it.uid == 1L }.flags)
        session.exists = 9
        runImmediate { model.applyChange(MailboxChange.Expunge(9)) }
        assertEquals(0, model.pendingNew)
        assertFalse(model.newMailUnnumbered)
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

    @Test
    fun advancedQueryRoundTripAndSearch() {
        val steps = listOf(
            AdvancedStep(false, "Subject", "budget"),
            AdvancedStep(true, "From", "ada"),
        )
        val text = checkNotNull(
            encodeAdvancedQuery(AdvancedCombiner.And, steps),
        )
        assertEquals("And\nYes\tSubject\tbudget\nNot\tFrom\tada", text)
        val parsed = parseAdvancedQuery(text)
        assertEquals(AdvancedCombiner.And, parsed?.combiner)
        assertEquals(steps, parsed?.steps)
        assertNull(encodeAdvancedQuery(AdvancedCombiner.And, listOf(AdvancedStep(false, "Subject", "bud\tget"))))
        assertNull(encodeAdvancedQuery(AdvancedCombiner.And, listOf(AdvancedStep(false, "Sub\nject", "budget"))))
        assertNull(parseAdvancedQuery("Both\nYes\tSubject\tbudget"))

        val session = FakeMailSession()
        session.arrivalRows = listOf(row(1), row(2), row(3))
        session.searchUids = listOf(9L, 8L)
        session.rows[9L] = row(9)
        session.rows[8L] = row(8)
        val model = IndexModel(session, MemorySettingsStore(AccountSettings()), "INBOX")
        runImmediate { model.loadWindow() }
        assertEquals(
            SelectAllTarget.EntireMailbox,
            selectAllTarget(model.filterActive || model.searchActive, model.order),
        )
        val searched = runImmediate { model.applyAdvanced(text) }
        assertEquals(listOf("Advanced"), session.searchKinds)
        assertEquals(listOf(text), session.searchCalls)
        assertTrue(session.textSearches.isEmpty())
        assertTrue(session.listCalls.isEmpty())
        assertTrue(session.selects.isEmpty())
        assertTrue(model.orderMailboxes.isEmpty())
        assertTrue(searched.all { it.mailbox.isEmpty() })
        assertFalse(blocksFolderSelection(model.orderMailboxes, "INBOX"))
        assertEquals(listOf(9L, 8L), model.order)
        assertEquals(listOf(9L, 8L), searched.map { it.uid })
        assertEquals(
            SelectAllTarget.Uids(model.order),
            selectAllTarget(model.filterActive || model.searchActive, model.order),
        )
        runImmediate { model.jumpToNewest() }
        assertEquals(listOf("Advanced", "Advanced"), session.searchKinds)
        assertEquals(listOf(text, text), session.searchCalls)
        val sorted = runImmediate { model.applyView(FolderView(SortKey.From, newestFirst = true)) }
        assertEquals(listOf("Advanced", "Advanced", "Advanced"), session.searchKinds)
        assertEquals(listOf(text, text, text), session.searchCalls)
        assertEquals(listOf(9L, 8L), sorted.map { it.uid })
        assertEquals(listOf(9L, 8L), model.order)
        assertTrue(session.sortCalls.isEmpty())
        runImmediate { model.applySearch("needle") }
        assertEquals(listOf("Advanced", "Advanced", "Advanced", "Subject"), session.searchKinds)
        assertEquals(listOf(text, text, text, "needle"), session.searchCalls)
        assertTrue(session.textSearches.isEmpty())
        assertTrue(session.listCalls.isEmpty())
        assertTrue(model.orderMailboxes.isEmpty())

        val failedSession = FakeMailSession()
        failedSession.arrivalRows = listOf(row(1, sequence = 4))
        val failed = IndexModel(failedSession, MemorySettingsStore(AccountSettings()), "INBOX")
        runImmediate { failed.loadWindow() }
        failedSession.failure = MailFailure("bad search")
        runImmediate { failed.applyAdvanced(text) }
        assertEquals("bad search", failed.notice)
        assertEquals(listOf(1L), failed.rows.map { it.uid })
        assertEquals(4, failed.rows.single().sequence)
        assertTrue(failedSession.textSearches.isEmpty())
        assertTrue(failedSession.listCalls.isEmpty())
    }

    @Test
    fun expandMailboxesAddsChildAndDoesNotLoop() {
        val expanded = runImmediate {
            expandMailboxes(listOf("INBOX")) { parent ->
                if (parent == "INBOX") {
                    listOf(FolderEntry("INBOX.Sent", "Sent", false, '.'))
                } else {
                    emptyList()
                }
            }
        }
        assertEquals(listOf("INBOX", "INBOX.Sent"), expanded)
        val looped = runImmediate {
            expandMailboxes(listOf("INBOX")) {
                listOf(
                    FolderEntry("INBOX", "INBOX", true, '.'),
                    FolderEntry("INBOX.Sent", "Sent", true, '.'),
                )
            }
        }
        assertEquals(listOf("INBOX", "INBOX.Sent"), looped)
    }

    @Test
    fun subtreeAdvancedSearchesEachFolder() {
        val steps = listOf(
            AdvancedStep(false, "Subject", "budget"),
            AdvancedStep(true, "From", "ada"),
        )
        val text = checkNotNull(encodeAdvancedQuery(AdvancedCombiner.And, steps))
        val session = scopedSession()
        val model = IndexModel(session, MemorySettingsStore(AccountSettings()), "INBOX")
        runImmediate { model.loadWindow() }
        val progress = ArrayList<Pair<Int, Int>>()
        val searched = runImmediate {
            model.applyAdvanced(text, SearchScope.Subtree) { current, total ->
                progress += current to total
            }
        }
        assertEquals(listOf("Advanced", "Advanced"), session.searchKinds)
        assertEquals(listOf(text, text), session.searchCalls)
        assertEquals(listOf("INBOX", "INBOX.Sent"), session.searchMailboxes)
        assertEquals("INBOX", session.selects.last())
        assertEquals(listOf(1 to 2, 2 to 2), progress)
        assertTrue(searched.any { it.mailbox == "INBOX.Sent" })
        assertTrue(blocksFolderSelection(model.orderMailboxes, "INBOX"))
        val orderBeforeNotice = model.order
        model.reportNotice("Open that folder to change these messages.")
        assertEquals("Open that folder to change these messages.", model.notice)
        assertEquals(orderBeforeNotice, model.order)
    }

    @Test
    fun subtreeAdvancedCancelKeepsTheFirstFolder() {
        val text = checkNotNull(
            encodeAdvancedQuery(
                AdvancedCombiner.And,
                listOf(AdvancedStep(false, "Subject", "budget")),
            ),
        )
        val session = scopedSession()
        val model = IndexModel(session, MemorySettingsStore(AccountSettings()), "INBOX")
        runImmediate { model.loadWindow() }
        val searched = runImmediate {
            model.applyAdvanced(
                text,
                SearchScope.Subtree,
                cancelled = { session.searchKinds.isNotEmpty() },
            )
        }
        assertEquals(listOf("Advanced"), session.searchKinds)
        assertEquals(listOf("INBOX"), session.searchMailboxes)
        assertEquals("INBOX", session.selects.last())
        assertTrue(session.selects.none { it == "INBOX.Sent" })
        assertTrue(searched.all { it.mailbox == "INBOX" })
        assertTrue(searched.isNotEmpty())
    }

    @Test
    fun countHitsCurrentDoesNotFetch() {
        val text = checkNotNull(
            encodeAdvancedQuery(
                AdvancedCombiner.And,
                listOf(AdvancedStep(false, "Subject", "budget")),
            ),
        )
        val session = FakeMailSession()
        session.searchCountResult = 7
        val result = runImmediate { countHits(session, "INBOX", text, SearchScope.Current) }
        assertEquals(SearchCount(7, emptyList()), result)
        assertEquals(listOf("Advanced" to text), session.searchCountCalls)
        assertTrue(session.fetchRequests.isEmpty())
        assertTrue(session.listCalls.isEmpty())
        assertTrue(session.searchKinds.isEmpty())
        assertTrue(session.selects.isEmpty())
    }

    @Test
    fun countHitsSkipsAFailedSelect() {
        val text = checkNotNull(
            encodeAdvancedQuery(
                AdvancedCombiner.And,
                listOf(AdvancedStep(false, "Subject", "budget")),
            ),
        )
        val session = scopedSession()
        session.failSelect += "INBOX.Sent"
        session.searchCountResult = 4
        val result = runImmediate { countHits(session, "INBOX", text, SearchScope.Subtree) }
        assertEquals(SearchCount(4, listOf("INBOX.Sent")), result)
        assertEquals(listOf("Advanced" to text), session.searchCountCalls)
        assertTrue(session.fetchRequests.isEmpty())
        assertTrue(session.searchKinds.isEmpty())
        assertEquals(listOf("INBOX", "INBOX.Sent"), session.selects)
    }

    @Test
    fun countHitsCurrentWithMultisearchStaysOneSearch() {
        val text = checkNotNull(
            encodeAdvancedQuery(
                AdvancedCombiner.And,
                listOf(AdvancedStep(false, "Subject", "budget")),
            ),
        )
        val session = FakeMailSession(setOf("MULTISEARCH"))
        session.searchCountResult = 7
        val result = runImmediate { countHits(session, "INBOX", text, SearchScope.Current) }
        assertEquals(SearchCount(7, emptyList()), result)
        assertEquals(listOf("Advanced" to text), session.searchCountCalls)
        assertTrue(session.scopeCountCalls.isEmpty())
        assertTrue(session.scopeCalls.isEmpty())
    }

    @Test
    fun countHitsSubtreeWithMultisearchIsOneScopeCount() {
        val text = checkNotNull(
            encodeAdvancedQuery(
                AdvancedCombiner.And,
                listOf(AdvancedStep(false, "Subject", "budget")),
            ),
        )
        val session = FakeMailSession(setOf("MULTISEARCH"))
        session.scopeCount = 5
        val progress = ArrayList<Pair<Int, Int>>()
        val result = runImmediate {
            countHits(session, "INBOX", text, SearchScope.Subtree) { current, total ->
                progress += current to total
            }
        }
        assertEquals(SearchCount(5, emptyList()), result)
        assertEquals(listOf(scopeCall("Subtree", "INBOX", "Advanced", text)), session.scopeCountCalls)
        assertTrue(session.scopeCalls.isEmpty())
        assertTrue(session.searchCountCalls.isEmpty())
        assertTrue(session.selects.isEmpty())
        assertTrue(session.listCalls.isEmpty())
        assertTrue(session.fetchRequests.isEmpty())
        assertEquals(listOf(1 to 1), progress)
    }

    @Test
    fun countHitsSubscribedWithMultisearchIsOneScopeCount() {
        val text = checkNotNull(
            encodeAdvancedQuery(
                AdvancedCombiner.And,
                listOf(AdvancedStep(false, "Subject", "budget")),
            ),
        )
        val session = FakeMailSession(setOf("MULTISEARCH"))
        session.scopeCount = 2
        val result = runImmediate { countHits(session, "INBOX", text, SearchScope.Subscribed) }
        assertEquals(SearchCount(2, emptyList()), result)
        assertEquals(listOf(scopeCall("Subscribed", "INBOX", "Advanced", text)), session.scopeCountCalls)
    }

    @Test
    fun fastSubtreeAdvancedUsesOneScopeSearch() {
        val text = checkNotNull(
            encodeAdvancedQuery(
                AdvancedCombiner.And,
                listOf(AdvancedStep(false, "Subject", "budget")),
            ),
        )
        val session = FakeMailSession(setOf("MULTISEARCH"))
        session.arrivalRows = listOf(row(1))
        session.scopeHits = listOf(
            MailboxUids("INBOX", listOf(9L, 8L)),
            MailboxUids("INBOX.Sent", listOf(4L)),
        )
        val model = IndexModel(session, MemorySettingsStore(AccountSettings()), "INBOX")
        runImmediate { model.loadWindow() }
        val searched = runImmediate { model.applyAdvanced(text, SearchScope.Subtree) }
        assertEquals(listOf(scopeCall("Subtree", "INBOX", "Advanced", text, emptyList())), session.scopeCalls)
        assertTrue(session.searchKinds.isEmpty())
        assertTrue(session.listCalls.isEmpty())
        assertTrue(searched.any { it.mailbox == "INBOX" })
        assertTrue(searched.any { it.mailbox == "INBOX.Sent" })
        assertEquals("INBOX", session.selects.last())
    }

    @Test
    fun fastAllPersonalUsesOneScopeSearch() {
        val text = checkNotNull(
            encodeAdvancedQuery(
                AdvancedCombiner.And,
                listOf(AdvancedStep(false, "Subject", "budget")),
            ),
        )
        val session = FakeMailSession(setOf("MULTISEARCH"))
        session.namespaceRows = listOf(Namespace("", '/', NamespaceKind.Personal))
        session.scopeHits = listOf(MailboxUids("INBOX", listOf(9L)))
        val model = IndexModel(session, MemorySettingsStore(AccountSettings()), "INBOX")
        runImmediate { model.applyAdvanced(text, SearchScope.All) }
        assertEquals("All", session.scopeCalls.single().scopeName)
        assertTrue(session.listCalls.isEmpty())
    }

    @Test
    fun allWithSharedNamespaceStaysOnTheFolderLoop() {
        val text = checkNotNull(
            encodeAdvancedQuery(
                AdvancedCombiner.And,
                listOf(AdvancedStep(false, "Subject", "budget")),
            ),
        )
        val session = FakeMailSession(setOf("MULTISEARCH"))
        session.namespaceRows = listOf(
            Namespace("", '/', NamespaceKind.Personal),
            Namespace("Shared/", '/', NamespaceKind.Shared),
        )
        val model = IndexModel(session, MemorySettingsStore(AccountSettings()), "INBOX")
        runImmediate { model.applyAdvanced(text, SearchScope.All) }
        assertTrue(session.scopeCalls.isEmpty())
        assertTrue(session.listCalls.isNotEmpty())
    }

    @Test
    fun fastScopeFailureKeepsThePreviousRow() {
        val text = checkNotNull(
            encodeAdvancedQuery(
                AdvancedCombiner.And,
                listOf(AdvancedStep(false, "Subject", "budget")),
            ),
        )
        val session = FakeMailSession(setOf("MULTISEARCH"))
        session.arrivalRows = listOf(row(1, sequence = 4))
        val model = IndexModel(session, MemorySettingsStore(AccountSettings()), "INBOX")
        runImmediate { model.loadWindow() }
        session.scopeFailure = MailFailure("search failed")
        runImmediate { model.applyAdvanced(text, SearchScope.Subtree) }
        assertEquals("search failed", model.notice)
        assertEquals(listOf(1L), model.rows.map { it.uid })
        assertTrue(model.orderMailboxes.isEmpty())
    }

    @Test
    fun jumpArrivalNewestLoadsSequenceAndRejectsOutside() {
        val session = folder(300)
        val model = model(session, StartRule.Newest, newestFirst = true)
        val initial = runImmediate { model.loadWindow() }
        val initialSequences = initial.map { it.sequence }
        val initialStart = model.startIndex
        val fetches = session.fetchRequests.size
        val searches = session.startSearches.size
        assertFalse(runImmediate { model.jumpToSequence(0) })
        assertFalse(runImmediate { model.jumpToSequence(301) })
        assertEquals(initialSequences, model.rows.map { it.sequence })
        assertEquals(initialStart, model.startIndex)
        assertEquals(fetches, session.fetchRequests.size)
        assertEquals(searches, session.startSearches.size)
        assertTrue(runImmediate { model.jumpToSequence(20) })
        assertTrue(model.rows.any { it.sequence == 20 })
        assertEquals(20, model.rows[model.startIndex].sequence)
        assertEquals(searches, session.startSearches.size)
        assertTrue(session.searchCalls.isEmpty())
        assertTrue(session.fetchRequests.size > fetches)
        assertEquals(IndexMode.ArrivalRange, session.fetchRequests.last().mode)
    }

    @Test
    fun jumpArrivalOldestUsesSequenceIndex() {
        val session = folder(300)
        val model = model(session, StartRule.Newest, newestFirst = false)
        runImmediate { model.loadWindow() }
        val searches = session.startSearches.size
        assertTrue(runImmediate { model.jumpToSequence(20) })
        assertEquals(20, model.rows[model.startIndex].sequence)
        assertEquals(searches, session.startSearches.size)
        assertTrue(session.searchCalls.isEmpty())
    }

    @Test
    fun jumpLoadedNonArrivalDoesNotRefetch() {
        val session = FakeMailSession()
        val uids = (200L downTo 1L).toList()
        session.sortUids = uids
        uids.forEach { session.rows[it] = row(it) }
        val store = MemorySettingsStore(
            AccountSettings(defaultView = FolderView(SortKey.From, newestFirst = true)),
        )
        val model = IndexModel(session, store, "INBOX")
        val loaded = runImmediate { model.loadWindow() }
        assertEquals(uids.take(IndexPageSize * 2), loaded.map { it.uid })
        val fetches = session.fetchRequests.size
        val sorts = session.sortCalls.size
        val searches = session.searchCalls.size
        assertTrue(runImmediate { model.jumpToSequence(loaded[4].sequence) })
        assertEquals(4, model.startIndex)
        assertEquals(loaded.map { it.uid }, model.rows.map { it.uid })
        assertFalse(runImmediate { model.jumpToSequence(1) })
        assertEquals(4, model.startIndex)
        assertEquals(loaded.map { it.uid }, model.rows.map { it.uid })
        assertTrue(model.rows.none { it.sequence == 1 })
        assertEquals(fetches, session.fetchRequests.size)
        assertEquals(sorts, session.sortCalls.size)
        assertEquals(searches, session.searchCalls.size)
    }

    @Test
    fun jumpFilterAndSearchStayOnLoadedRows() {
        val filtered = folder(300)
        filtered.searchUids = listOf(12L, 40L)
        val filterModel = model(filtered, StartRule.Newest, newestFirst = true)
        runImmediate { filterModel.loadWindow() }
        runImmediate { filterModel.applyCriterion("From", "ada", false, "From") }
        val filterRows = filterModel.rows.map { it.uid }
        assertEquals(listOf(40L, 12L), filterRows)
        val filterFetches = filtered.fetchRequests.size
        val filterSearches = filtered.searchCalls.size
        assertTrue(runImmediate { filterModel.jumpToSequence(40) })
        assertEquals(0, filterModel.startIndex)
        assertFalse(runImmediate { filterModel.jumpToSequence(20) })
        assertEquals(0, filterModel.startIndex)
        assertEquals(filterRows, filterModel.rows.map { it.uid })
        assertEquals(filterFetches, filtered.fetchRequests.size)
        assertEquals(filterSearches, filtered.searchCalls.size)

        val searched = folder(30)
        searched.searchUids = listOf(4L, 9L)
        val searchModel = model(searched, StartRule.Newest, newestFirst = true)
        runImmediate { searchModel.loadWindow() }
        runImmediate { searchModel.applySearch("ada") }
        val searchRows = searchModel.rows.map { it.uid }
        assertEquals(listOf(9L, 4L), searchRows)
        val searchFetches = searched.fetchRequests.size
        val searchCalls = searched.searchCalls.size
        assertTrue(runImmediate { searchModel.jumpToSequence(4) })
        assertEquals(searchModel.rows.indexOfFirst { it.sequence == 4 }, searchModel.startIndex)
        assertFalse(runImmediate { searchModel.jumpToSequence(8) })
        assertEquals(searchRows, searchModel.rows.map { it.uid })
        assertEquals(searchFetches, searched.fetchRequests.size)
        assertEquals(searchCalls, searched.searchCalls.size)
    }

    @Test
    fun nextUnreadArrivalNewestPicksGreatestBelowAnchor() {
        val session = folder(100)
        session.unseenSeq.addAll(listOf(10, 40, 70, 100))
        session.deletedSeq.add(70)
        val model = model(session, StartRule.Newest, newestFirst = true)
        runImmediate { model.loadWindow() }
        assertTrue(runImmediate { model.jumpToSequence(100) })
        assertEquals(100, model.rows[model.startIndex].sequence)
        assertTrue(runImmediate { model.nextUnread() })
        assertEquals(40, model.rows[model.startIndex].sequence)
        val search = session.startSearches.last()
        assertEquals(StartRule.FirstUnseen.name, search.rule)
        assertEquals(false, search.byUid)
        assertEquals(SearchEdge.All.name, search.edge)
        assertEquals("UNDELETED UNSEEN", search.key)
        assertTrue(runImmediate { model.jumpToSequence(10) })
        val stayed = model.rows.map { it.sequence }
        val start = model.startIndex
        assertEquals(10, model.rows[start].sequence)
        assertFalse(runImmediate { model.nextUnread() })
        assertEquals(stayed, model.rows.map { it.sequence })
        assertEquals(start, model.startIndex)
        assertNull(model.notice)
    }

    @Test
    fun nextUnreadArrivalOldestPicksSmallestAboveAnchor() {
        val session = folder(100)
        session.unseenSeq.addAll(listOf(10, 40, 100))
        val model = model(session, StartRule.Newest, newestFirst = false)
        runImmediate { model.loadWindow() }
        assertTrue(runImmediate { model.jumpToSequence(10) })
        assertTrue(runImmediate { model.nextUnread() })
        assertEquals(40, model.rows[model.startIndex].sequence)
        assertEquals(false, session.startSearches.last().byUid)
    }

    @Test
    fun nextUnreadArrivalAnchorZeroUsesExtreme() {
        val newestSession = folder(30)
        newestSession.unseenSeq.addAll(listOf(10, 25))
        val newest = model(newestSession, StartRule.Newest, newestFirst = true)
        runImmediate { newest.loadWindow() }
        assertTrue(newest.rows.size < IndexPageSize)
        runImmediate { newest.onFirstVisible(IndexPageSize - 1) }
        assertTrue(runImmediate { newest.nextUnread() })
        assertEquals(25, newest.rows[newest.startIndex].sequence)
        val oldestSession = folder(30)
        oldestSession.unseenSeq.addAll(listOf(10, 25))
        val oldest = model(oldestSession, StartRule.Newest, newestFirst = false)
        runImmediate { oldest.loadWindow() }
        runImmediate { oldest.onFirstVisible(IndexPageSize - 1) }
        assertTrue(runImmediate { oldest.nextUnread() })
        assertEquals(10, oldest.rows[oldest.startIndex].sequence)
    }

    @Test
    fun nextUnreadSortedWalksOrderAfterVisibleUid() {
        val session = FakeMailSession()
        val uids = listOf(1L, 4L, 9L, 12L)
        session.sortUids = uids
        uids.forEach { session.rows[it] = row(it) }
        session.unseenUids.addAll(listOf(4L, 12L))
        val store = MemorySettingsStore(
            AccountSettings(
                inboxStart = StartRule.Newest,
                defaultView = FolderView(SortKey.From, newestFirst = true),
            ),
        )
        val model = IndexModel(session, store, "INBOX")
        runImmediate { model.loadWindow() }
        runImmediate { model.onFirstVisible(0) }
        assertEquals(1L, model.rows[0].uid)
        assertTrue(runImmediate { model.nextUnread() })
        assertEquals(4L, model.rows[model.startIndex].uid)
        val search = session.startSearches.single()
        assertEquals(StartRule.FirstUnseen.name, search.rule)
        assertEquals(true, search.byUid)
        assertEquals(SearchEdge.All.name, search.edge)
        assertTrue(runImmediate { model.nextUnread() })
        assertEquals(12L, model.rows[model.startIndex].uid)
        val stayed = model.rows.map { it.uid }
        val start = model.startIndex
        assertFalse(runImmediate { model.nextUnread() })
        assertEquals(stayed, model.rows.map { it.uid })
        assertEquals(start, model.startIndex)
        assertNull(model.notice)
    }

    @Test
    fun nextUnreadSearchFailureSetsNoticeAndDoesNotMove() {
        val session = FakeMailSession()
        val uids = listOf(1L, 4L, 9L)
        session.sortUids = uids
        uids.forEach { session.rows[it] = row(it) }
        session.unseenUids.add(9L)
        val store = MemorySettingsStore(
            AccountSettings(
                inboxStart = StartRule.Newest,
                defaultView = FolderView(SortKey.From, newestFirst = true),
            ),
        )
        val model = IndexModel(session, store, "INBOX")
        runImmediate { model.loadWindow() }
        runImmediate { model.onFirstVisible(0) }
        val stayed = model.rows.map { it.uid }
        val start = model.startIndex
        session.startFailure = MailFailure("search failed")
        assertFalse(runImmediate { model.nextUnread() })
        assertEquals("search failed", model.notice)
        assertEquals(stayed, model.rows.map { it.uid })
        assertEquals(start, model.startIndex)
    }

    @Test
    fun nextUnreadFilterWalksUidsAfterVisible() {
        val session = folder(30)
        session.searchUids = listOf(4L, 9L, 12L)
        session.unseenUids.addAll(listOf(4L, 12L))
        val model = model(session, StartRule.Newest, newestFirst = true)
        runImmediate { model.loadWindow() }
        val filtered = runImmediate { model.applyCriterion("From", "ada", narrow = false, label = "From") }
        assertEquals(listOf(12L, 9L, 4L), filtered.map { it.uid })
        runImmediate { model.onFirstVisible(0) }
        assertTrue(runImmediate { model.nextUnread() })
        assertEquals(4L, model.rows[model.startIndex].uid)
        assertEquals(true, session.startSearches.last().byUid)
    }

    @Test
    fun nextUnreadThreadLandsOnThreadWithHiddenUid() {
        val session = FakeMailSession()
        session.threadNode = ThreadNode(
            uid = null,
            children = listOf(
                ThreadNode(3L, emptyList()),
                ThreadNode(5L, listOf(ThreadNode(1L, emptyList()))),
                ThreadNode(9L, listOf(ThreadNode(8L, emptyList()))),
            ),
        )
        listOf(3L, 5L, 1L, 9L, 8L).forEach { session.rows[it] = row(it) }
        session.unseenUids.add(8L)
        val store = MemorySettingsStore(
            AccountSettings(
                inboxStart = StartRule.Newest,
                defaultView = FolderView(SortKey.ThreadReferences, newestFirst = false),
            ),
        )
        val model = IndexModel(session, store, "INBOX")
        val loaded = runImmediate { model.loadWindow() }
        assertEquals(listOf(3L, 5L, 9L), loaded.map { it.uid })
        runImmediate { model.onFirstVisible(0) }
        assertTrue(runImmediate { model.nextUnread() })
        assertEquals(9L, model.rows[model.startIndex].uid)
        assertEquals(true, session.startSearches.last().byUid)
        val stayed = model.rows.map { it.uid }
        assertFalse(runImmediate { model.nextUnread() })
        assertEquals(stayed, model.rows.map { it.uid })
        assertNull(model.notice)
    }
}

private fun scopedSession(): FakeMailSession {
    val session = FakeMailSession()
    session.arrivalRows = listOf(row(1), row(2), row(3))
    session.searchUids = listOf(9L, 8L)
    session.rows[9L] = row(9)
    session.rows[8L] = row(8)
    session.levels = { _, parent ->
        if (parent == "INBOX") {
            listOf(FolderEntry("INBOX.Sent", "Sent", false, '.'))
        } else {
            emptyList()
        }
    }
    return session
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

private data class ScopeCall(
    val scopeName: String,
    val home: String,
    val kind: String,
    val argument: String,
    val selects: List<String> = emptyList(),
)

private fun scopeCall(
    scopeName: String,
    home: String,
    kind: String,
    argument: String,
    selects: List<String> = emptyList(),
) = ScopeCall(scopeName, home, kind, argument, selects)

private class FakeMailSession(
    override var capabilities: Set<String> = emptySet(),
) : MailSession {
    val fetchRequests = mutableListOf<IndexRequest>()
    val sortCalls = mutableListOf<Pair<SortKey, Boolean>>()
    val threadCalls = mutableListOf<SortKey>()
    val searchCalls = mutableListOf<String>()
    val searchKinds = mutableListOf<String>()
    val searchMailboxes = mutableListOf<String>()
    val searchCountCalls = mutableListOf<Pair<String, String>>()
    var searchCountResult: Int = 0
    val scopeCalls = mutableListOf<ScopeCall>()
    val scopeCountCalls = mutableListOf<ScopeCall>()
    var scopeHits: List<MailboxUids> = emptyList()
    var scopeCount: Int = 0
    var scopeFailure: MailFailure? = null
    var namespaceRows: List<Namespace>? = null
    val textSearches = mutableListOf<String>()
    val listCalls = mutableListOf<Triple<String, String?, Boolean>>()
    var levels: (String, String?) -> List<FolderEntry> = { _, _ -> emptyList() }
    val selects = mutableListOf<String>()
    val failSelect = mutableSetOf<String>()
    var selectedName: String = ""
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
    var knownTrashName: String = ""
    val uidExpunges = mutableListOf<List<Long>>()
    val watchMailboxes = mutableListOf<String>()
    var stopWatchCount: Int = 0

    override suspend fun open(account: AccountSettings): OpenResult = unused()

    override suspend fun namespaces(): List<Namespace> {
        val rows = namespaceRows
        if (rows != null) return rows
        return unused()
    }

    override suspend fun listLevel(prefix: String, parentMailbox: String?, unreadCounts: Boolean): List<FolderEntry> {
        listCalls += Triple(prefix, parentMailbox, unreadCounts)
        return levels(prefix, parentMailbox)
    }

    override suspend fun select(mailbox: String): SelectResult {
        selects += mailbox
        if (mailbox in failSelect) throw MailFailure("select failed")
        selectedName = mailbox
        return SelectResult(uidValidity = 1L, uidNext = 1L, exists = exists.coerceAtLeast(0))
    }

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

    override suspend fun storeFlagsAll(add: Set<String>, remove: Set<String>) {
        throwIfArmed()
        stores += FlagWrite(emptyList(), add, remove)
    }

    override suspend fun uidExpungeDeleted() {
        throwIfArmed()
        expungeCount += 1
    }

    override suspend fun uidExpunge(uids: List<Long>) {
        throwIfArmed()
        uidExpunges += uids
    }

    override suspend fun knownTrash(): String = knownTrashName

    override suspend fun copyThenDelete(uids: List<Long>, targetMailbox: String) {
        throwIfArmed()
        copies += CopyWrite(uids, targetMailbox)
    }

    override suspend fun searchText(query: String): List<Long> {
        searchCalls += query
        textSearches += query
        throwIfArmed()
        return searchUids
    }

    override suspend fun searchCriterion(kind: String, argument: String): List<Long> {
        searchCalls += argument
        searchKinds += kind
        searchMailboxes += selectedName
        throwIfArmed()
        return searchUids
    }

    override suspend fun searchCount(kind: String, argument: String): Int {
        searchCountCalls += kind to argument
        return searchCountResult
    }

    override suspend fun searchScope(
        scopeName: String,
        home: String,
        kind: String,
        argument: String,
    ): List<MailboxUids> {
        scopeCalls += ScopeCall(scopeName, home, kind, argument, selects.toList())
        val pending = scopeFailure
        if (pending != null) throw pending
        return scopeHits
    }

    override suspend fun searchScopeCount(
        scopeName: String,
        home: String,
        kind: String,
        argument: String,
    ): Int {
        scopeCountCalls += ScopeCall(scopeName, home, kind, argument, selects.toList())
        val pending = scopeFailure
        if (pending != null) throw pending
        return scopeCount
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
