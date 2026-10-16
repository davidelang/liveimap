package org.dlang.liveimap.session

import org.dlang.liveimap.settings.SortKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ClientThreadTest {
    @Test
    fun rePairIsOneThread() {
        val rows = listOf(
            ThreadHeader(2L, "", "", "", "Re: Hello"),
            ThreadHeader(1L, "", "", "", "Hello"),
        )
        val tree = clientThreads(rows, SortKey.ThreadOrderedSubject)
        assertNull(tree.uid)
        assertEquals(1, tree.children.size)
        assertEquals(1L, tree.children[0].uid)
        assertEquals(listOf(2L), tree.children[0].children.map { it.uid })
    }

    @Test
    fun orderedSubjectGroupsByBaseSubjectAndDate() {
        val rows = listOf(
            ThreadHeader(2L, "", "", "", "[list] Re: x", "Tue, 2 Jan 2024 00:00:00 +0000"),
            ThreadHeader(1L, "", "", "", "x", "Mon, 1 Jan 2024 00:00:00 +0000"),
            ThreadHeader(3L, "", "", "", "Fw: x", "Wed, 3 Jan 2024 00:00:00 +0000"),
        )
        val tree = clientThreads(rows, SortKey.ThreadOrderedSubject)
        assertNull(tree.uid)
        assertEquals(1, tree.children.size)
        assertEquals(1L, tree.children[0].uid)
        assertEquals(listOf(2L, 3L), tree.children[0].children.map { it.uid })
    }

    @Test
    fun orderedSubjectRootsOrderByDate() {
        val rows = listOf(
            ThreadHeader(1L, "", "", "", "alpha", "Wed, 3 Jan 2024 00:00:00 +0000"),
            ThreadHeader(2L, "", "", "", "beta", "Mon, 1 Jan 2024 00:00:00 +0000"),
        )
        val tree = clientThreads(rows, SortKey.ThreadOrderedSubject)
        assertNull(tree.uid)
        assertEquals(listOf(2L, 1L), tree.children.map { it.uid })
    }

    @Test
    fun referencesChildHangsOffParent() {
        val rows = listOf(
            ThreadHeader(1L, "<a@x>", "", "", "Hello"),
            ThreadHeader(2L, "<b@x>", "<a@x>", "", "Re: Hello"),
        )
        val tree = clientThreads(rows, SortKey.ThreadReferences)
        assertNull(tree.uid)
        assertEquals(1L, tree.children.single().uid)
        assertEquals(2L, tree.children.single().children.single().uid)
    }
}
