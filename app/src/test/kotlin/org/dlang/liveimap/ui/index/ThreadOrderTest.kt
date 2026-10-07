package org.dlang.liveimap.ui.index

import org.dlang.liveimap.session.ThreadNode
import org.junit.Assert.assertEquals
import org.junit.Test

class ThreadOrderTest {
    @Test
    fun orderedThreadUidsOrdersRootsByLargestUid() {
        val nested = ThreadNode(8L, listOf(ThreadNode(1L, emptyList()), ThreadNode(4L, emptyList())))
        val root = ThreadNode(
            uid = null,
            children = listOf(
                ThreadNode(2L, emptyList()),
                nested,
            ),
        )
        assertEquals(listOf(8L, 1L, 4L, 2L), orderedThreadUids(root, newestFirst = true))
        assertEquals(listOf(2L, 8L, 1L, 4L), orderedThreadUids(root, newestFirst = false))
        assertEquals(listOf(1L, 4L), nested.children.map { it.uid })

        val uidless = ThreadNode(
            uid = null,
            children = listOf(
                ThreadNode(null, listOf(ThreadNode(6L, emptyList()))),
                ThreadNode(9L, emptyList()),
            ),
        )
        assertEquals(listOf(9L, 6L), orderedThreadUids(uidless, newestFirst = true))
        assertEquals(listOf(6L, 9L), orderedThreadUids(uidless, newestFirst = false))
        assertEquals(emptyList<Long>(), orderedThreadUids(ThreadNode(null, emptyList()), newestFirst = true))
    }

    @Test
    fun collapsedThreadsOrdersRootsAndHidden() {
        val tree = ThreadNode(
            uid = null,
            children = listOf(
                ThreadNode(5L, listOf(ThreadNode(1L, emptyList()))),
                ThreadNode(3L, emptyList()),
                ThreadNode(9L, listOf(ThreadNode(4L, emptyList()), ThreadNode(2L, emptyList()))),
            ),
        )
        val oldest = collapsedThreads(tree, newestFirst = false)
        assertEquals(listOf(3L, 5L, 9L), oldest.map { it.rootUid })
        assertEquals(emptyList<Long>(), oldest[0].hiddenUids)
        assertEquals(listOf(1L), oldest[1].hiddenUids)
        assertEquals(listOf(4L, 2L), oldest[2].hiddenUids)
        assertEquals(listOf(9L, 5L, 3L), collapsedThreads(tree, newestFirst = true).map { it.rootUid })
        val wrapped = collapsedThreads(
            ThreadNode(null, listOf(ThreadNode(6L, emptyList()))),
            newestFirst = true,
        )
        assertEquals(6L, wrapped.single().rootUid)
        assertEquals(emptyList<Long>(), wrapped.single().hiddenUids)
        val dropped = collapsedThreads(
            ThreadNode(null, listOf(ThreadNode(null, emptyList()), ThreadNode(4L, emptyList()))),
            newestFirst = false,
        )
        assertEquals(listOf(4L), dropped.map { it.rootUid })
        val siblings = collapsedThreads(
            ThreadNode(
                uid = null,
                children = listOf(
                    ThreadNode(
                        uid = null,
                        children = listOf(
                            ThreadNode(1L, emptyList()),
                            ThreadNode(2L, emptyList()),
                        ),
                    ),
                ),
            ),
            newestFirst = true,
        )
        assertEquals(listOf(2L, 1L), siblings.map { it.rootUid })
        assertEquals(emptyList<Long>(), siblings[0].hiddenUids)
        assertEquals(emptyList<Long>(), siblings[1].hiddenUids)
        val nested = collapsedThreads(
            ThreadNode(
                uid = null,
                children = listOf(
                    ThreadNode(
                        5L,
                        listOf(
                            ThreadNode(
                                null,
                                listOf(ThreadNode(1L, emptyList()), ThreadNode(2L, emptyList())),
                            ),
                        ),
                    ),
                ),
            ),
            newestFirst = false,
        )
        assertEquals(listOf(5L), nested.map { it.rootUid })
        assertEquals(listOf(1L, 2L), nested.single().hiddenUids)
    }
}
