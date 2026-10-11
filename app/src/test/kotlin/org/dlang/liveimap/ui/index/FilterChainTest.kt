package org.dlang.liveimap.ui.index

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FilterChainTest {
    @Test
    fun chainIsOneAndSearch() {
        assertNull(combinedFilterSearch(emptyList()))
        assertEquals("New" to "", combinedFilterSearch(listOf(AppliedFilter("Show new", "", "New"))))
        val chain = listOf(
            AppliedFilter("From", "ada", "From"),
            AppliedFilter("Show not deleted", "", "NotDeleted"),
            AppliedFilter("Thread", "4,7:9", "Uid"),
        )
        assertEquals(
            "Advanced" to "And\nYes\tFrom\tada\nYes\tNotDeleted\t\nYes\tUid\t4,7:9",
            combinedFilterSearch(chain),
        )
    }

    @Test
    fun advancedFiltersFlattenOrNest() {
        val and = encodeAdvancedQuery(AdvancedCombiner.And, listOf(AdvancedStep(true, "Subject", "x")))!!
        val or = encodeAdvancedQuery(
            AdvancedCombiner.Or,
            listOf(AdvancedStep(false, "From", "a"), AdvancedStep(false, "To", "b")),
        )!!
        val (kind, arg) = combinedFilterSearch(
            listOf(AppliedFilter("A", and, "Advanced"), AppliedFilter("B", or, "Advanced"), AppliedFilter("N", "", "New")),
        )!!
        assertEquals("Advanced", kind)
        assertEquals(
            "And\nNot\tSubject\tx\nYes\tGroup\tOr\u001eYes\u001fFrom\u001fa\u001eYes\u001fTo\u001fb\nYes\tNew\t",
            arg,
        )
    }

    @Test
    fun uidSetsCollapseRuns() {
        assertEquals("1,3:5,9", uidSetText(listOf(9L, 4L, 1L, 3L, 5L, 4L)))
        assertEquals("", uidSetText(emptyList()))
    }

    @Test
    fun threadSubtreeIsTheMessageAndItsReplies() {
        // 1 -> (2 -> 3), 4      preorder: 1, 2, 3, 4
        val plan = listOf(CollapsedThread(1L, listOf(2L, 3L, 4L), mapOf(2L to 1, 3L to 2, 4L to 1)), CollapsedThread(9L, emptyList()))
        assertEquals(listOf(1L, 2L, 3L, 4L), threadSubtree(plan, 1L))
        assertEquals(listOf(2L, 3L), threadSubtree(plan, 2L))
        assertEquals(listOf(4L), threadSubtree(plan, 4L))
        assertEquals(listOf(9L), threadSubtree(plan, 9L))
        assertEquals(listOf(42L), threadSubtree(plan, 42L))
    }

    @Test
    fun chipTextHidesUidSetsAndThreadChipUsesSubject() {
        assertEquals("Show this thread: Lab thread 288", threadChipLabel("Show this thread", "Re: Fwd: RE[2]: Lab thread 288"))
        assertEquals("Show this thread", threadChipLabel("Show this thread", null))
        assertEquals("Show this thread: x", filterChipText(AppliedFilter("Show this thread: x", "4,7:9", "Uid")))
        assertEquals("From ada", filterChipText(AppliedFilter("From", "ada", "From")))
        assertEquals(
            "And\nYes\tFrom\tada\nYes\tUid\t4",
            filterChainGroup(listOf(AppliedFilter("From", "ada", "From"), AppliedFilter("t", "4", "Uid"))),
        )
    }
}
