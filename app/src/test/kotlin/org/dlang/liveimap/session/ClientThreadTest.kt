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
