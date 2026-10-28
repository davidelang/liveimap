package org.dlang.liveimap.ui.index

import org.dlang.liveimap.session.FolderEntry
import org.dlang.liveimap.session.IndexRow
import org.dlang.liveimap.session.MailFailure
import org.dlang.liveimap.session.MessageOrder
import org.dlang.liveimap.session.MessageRef
import org.dlang.liveimap.settings.AccountSettings
import org.dlang.liveimap.settings.DateFormat
import org.dlang.liveimap.settings.DeletePolicy
import org.dlang.liveimap.settings.SwipeAction
import org.dlang.liveimap.settings.SwipeBinding
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

class IndexWindowTest {
    @Test
    fun effectiveDeletePolicyFallbacks() {
        assertEquals(
            DeletePolicy.DeletePermanently,
            effectiveDeletePolicy(DeletePolicy.MarkDeleted, inTrash = true, uidPlus = true, trashKnown = true, trashNamedByUser = true),
        )
        assertEquals(
            DeletePolicy.MarkDeleted,
            effectiveDeletePolicy(DeletePolicy.DeletePermanently, inTrash = true, uidPlus = false, trashKnown = true, trashNamedByUser = true),
        )
        assertEquals(
            DeletePolicy.MarkDeleted,
            effectiveDeletePolicy(DeletePolicy.DeletePermanently, inTrash = false, uidPlus = false, trashKnown = false, trashNamedByUser = true),
        )
        assertEquals(
            DeletePolicy.MarkDeleted,
            effectiveDeletePolicy(DeletePolicy.MoveToTrash, inTrash = false, uidPlus = true, trashKnown = false, trashNamedByUser = true),
        )
        assertEquals(
            DeletePolicy.MoveToTrash,
            effectiveDeletePolicy(DeletePolicy.MoveToTrash, inTrash = false, uidPlus = false, trashKnown = true, trashNamedByUser = true),
        )
        assertEquals(
            DeletePolicy.DeletePermanently,
            effectiveDeletePolicy(DeletePolicy.DeletePermanently, inTrash = false, uidPlus = true, trashKnown = false, trashNamedByUser = true),
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
        assertEquals(SwipeVisual("primaryContainer", "arrow_forward"), swipeVisual(SwipeAction.Next))
        assertEquals(SwipeVisual("primaryContainer", "arrow_back"), swipeVisual(SwipeAction.Previous))
    }

    private fun screenSource(): String = listOf(
        "src/main/kotlin/org/dlang/liveimap/ui/index/MessageIndexScreen.kt",
        "app/src/main/kotlin/org/dlang/liveimap/ui/index/MessageIndexScreen.kt",
    ).map { java.io.File(it) }.first { it.exists() }.readText()

    @Test
    fun indexStatusDescriptionAndSequenceWidth() {
        val words = arrayOf("forwarded", "replied", "flagged", "to me", "has attachment")
        assertEquals(
            "forwarded, flagged",
            indexStatusDescription(
                setOf("\$Forwarded", "\\Answered", "\\Flagged"),
                toMe = false,
                hasAttachment = false,
                words[0],
                words[1],
                words[2],
                words[3],
                words[4],
            ),
        )
        assertEquals(
            "replied, to me",
            indexStatusDescription(
                setOf("\\Answered"),
                toMe = true,
                hasAttachment = false,
                words[0],
                words[1],
                words[2],
                words[3],
                words[4],
            ),
        )
        val statusFn = screenSource().substringAfter("fun indexStatusDescription(").substringBefore("\nfun ")
        for (phrase in words) assertFalse(statusFn.contains(phrase))
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
        val customYear = dateColumnSamples(DateFormat.Custom, "yyyy", words)
        assertFalse(customYear.contains("bad date pattern"))
        assertEquals(listOf("2024"), customYear)
        assertEquals(
            listOf("bad date pattern"),
            dateColumnSamples(DateFormat.Custom, "", words),
        )
        assertEquals(
            listOf("bad date pattern"),
            dateColumnSamples(DateFormat.Custom, "[", words),
        )
        val weekdays = dateColumnSamples(DateFormat.Custom, "EEEE", words)
        assertTrue(weekdays.contains("Wednesday"))
        assertFalse(weekdays.contains("bad date pattern"))
        val months = dateColumnSamples(DateFormat.Custom, "MMMM", words)
        assertTrue(months.contains("September"))
        assertFalse(months.contains("bad date pattern"))
        assertEquals(
            dateColumnSamples(DateFormat.Short, "", words),
            dateColumnSamples(DateFormat.Short, "yyyy-MM-dd", words),
        )
    }

    @Test
    fun wideSearchCountIsTheMatchCount() {
        assertEquals("10 of 300", indexMatchCountText(10, 300, false, SearchScope.Current))
        assertEquals("10 of 300", indexMatchCountText(10, 300, true, SearchScope.Current))
        assertEquals("10 of 10", indexMatchCountText(10, 0, true, SearchScope.Current))
        assertEquals("1200", indexMatchCountText(1200, 300, true, SearchScope.Subtree))
        assertEquals("1200", indexMatchCountText(1200, 300, true, SearchScope.Subscribed))
        assertEquals("1200", indexMatchCountText(1200, 300, true, SearchScope.All))
        assertEquals("10 of 300", indexMatchCountText(10, 300, false, SearchScope.All))
        assertFalse(indexMatchCountText(1200, 300, true, SearchScope.Subtree) == "1200 of 300")
        assertFalse(indexMatchCountText(1200, 300, true, SearchScope.Subscribed) == "1200 of 300")
        assertFalse(indexMatchCountText(1200, 300, true, SearchScope.All) == "1200 of 300")
    }

    @Test
    fun followingUndeletedSkipsAlreadyDeleted() {
        assertEquals(4L, followingUndeleted(listOf(1L, 2L, 3L, 4L), setOf(2L, 3L), 1L))
        assertNull(followingUndeleted(listOf(1L, 2L, 3L), setOf(2L, 3L), 1L))
        assertEquals(2L, followingUndeleted(listOf(1L, 2L), emptySet(), 1L))
        assertEquals(6L, followingUid(listOf(5L, 6L, 7L), 5L))
        OpenMessageOrder.clear()
        OpenMessageOrder.publish(
            "acct-a", 77L,
            "INBOX",
            longArrayOf(1L, 2L, 3L),
            listOf(row(1), row(2, flags = setOf("\\Deleted")), row(3)),
        )
        assertEquals(3L, OpenMessageOrder.followingAfterDelete(1L))
        assertTrue(OpenMessageOrder.matches("acct-a", "INBOX", 77L))
        assertFalse(OpenMessageOrder.matches("acct-b", "INBOX", 77L))
        assertFalse(OpenMessageOrder.matches("acct-a", "INBOX", 78L))
        assertFalse(OpenMessageOrder.matches("acct-a", "INBOX", 0L))
        var missingEpoch = false
        try {
            OpenMessageOrder.publish("acct-a", 0L, "INBOX", longArrayOf(1L), listOf(row(1)))
        } catch (failure: MailFailure) {
            missingEpoch = true
        }
        assertTrue(missingEpoch)
        OpenMessageOrder.clear()
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
        val filtered = selectAllTarget(true, testOrder(longArrayOf(2L, 5L)))
        assertEquals(listOf(2L, 5L), (filtered as SelectAllTarget.Uids).uids.asList())
        assertEquals(SelectAllTarget.EntireMailbox, selectAllTarget(false, testOrder(LongArray(0))))
        val huge = LongArray(200_000) { it.toLong() + 1 }
        val order = testOrder(huge)
        val all = selectAllTarget(true, order) as SelectAllTarget.Uids
        assertSame(order.uids, all.uids)
    }

    @Test
    fun selectAllSplitNamesEachFolderOrTheWholeMailbox() {
        val order = testOrder(longArrayOf(7L, 7L, 8L))
        val split = selectAllSplit("INBOX", order, listOf("INBOX", "Sent", "Sent"), false, false)
        val folders = split as SelectAllPlan.Folders
        assertEquals(listOf(7L), folders.homeUids.asList())
        assertEquals(listOf("Sent" to 7L, "Sent" to 8L), folders.foreign)
        assertEquals(
            SelectAllPlan.EntireMailbox,
            selectAllSplit("INBOX", order, listOf("INBOX", "INBOX", "INBOX"), false, false),
        )
        assertEquals(
            SelectAllPlan.EntireMailbox,
            selectAllSplit("INBOX", order, emptyList(), false, false),
        )
        val filtered = selectAllSplit("INBOX", testOrder(longArrayOf(5L, 6L)), emptyList(), true, false)
        assertEquals(listOf(5L, 6L), (filtered as SelectAllPlan.Uids).uids.asList())
        assertEquals(
            SelectAllPlan.EntireMailbox,
            selectAllSplit("INBOX", order, listOf("INBOX", "Sent", "Sent"), false, true),
        )
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
}

private fun testOrder(uids: LongArray) = MessageOrder(
    uids, ShortArray(0), "INBOX", "acct", 1L,
    LongArray(0), LongArray(0), ByteArray(0), IntArray(0), IntArray(0),
)

private fun row(uid: Long, sequence: Int = uid.toInt(), flags: Set<String> = emptySet()) = IndexRow(
    ref = MessageRef("acct-a", "INBOX", 77L, uid),
    sequence = sequence, flags = flags, internalDateEpoch = uid, size = 10,
    from = "from$uid", subject = "subject$uid", envelopeDate = "date$uid", preview = "preview$uid",
)

private fun <T> runImmediate(block: suspend () -> T): T {
    var result: Result<T>? = null
    block.startCoroutine(Continuation(EmptyCoroutineContext) { result = it })
    return result!!.getOrThrow()
}
