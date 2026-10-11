package org.dlang.liveimap.session

import org.dlang.liveimap.settings.SortKey
import org.dlang.liveimap.ui.compose.attachmentParts
import org.dlang.liveimap.ui.compose.nestedLeafParts
import org.dlang.liveimap.ui.compose.textPart
import org.dlang.liveimap.ui.index.collapsedThreads
import org.dlang.liveimap.ui.index.orderedThreadUids
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Sender-controlled nesting (MIME trees, References chains) must not recurse without bound. */
class DeepNestingTest {
    private fun depthOf(node: ThreadNode): Int {
        var best = 0
        val stack = ArrayDeque(listOf(node to 0))
        while (stack.isNotEmpty()) {
            val (n, d) = stack.removeLast()
            if (d > best) best = d
            n.children.forEach { stack.addLast(it to d + 1) }
        }
        return best
    }

    @Test(timeout = 30_000)
    fun longReferencesChainKeepsItsFullDepthAndEveryMessage() {
        val n = 20_000
        val rows = (1..n).map { i ->
            ThreadHeader(i.toLong(), "<m$i@x>", if (i > 1) "<m${i - 1}@x>" else "", "", "chain")
        }
        val root = clientThreads(rows, SortKey.ThreadReferences)
        assertEquals(n, depthOf(root))   // placeholder root + one level per reply: no depth limit
        assertEquals(n, root.messageCount())
        assertEquals(root, clientThreads(rows, SortKey.ThreadReferences))   // iterative equals on a deep tree
        root.hashCode()
        root.toString()
        val uids = orderedThreadUids(root, newestFirst = false)
        assertEquals((1L..n.toLong()).toList(), uids)
        val collapsed = collapsedThreads(root, newestFirst = false)
        assertEquals(1, collapsed.size)
        assertEquals(n - 1, collapsed[0].depthByUid.values.max())
    }

    private fun nested(levels: Int): MimePart {
        var part = MimePart("x", "text", "plain", "", null, 1, emptyList())
        repeat(levels) { part = MimePart("x", "multipart", "mixed", "", null, 0, listOf(part)) }
        return part
    }

    @Test(timeout = 30_000)
    fun deepMimeTreeDoesNotOverflow() {
        val deep = nested(20_000)
        assertNull(textPart(deep, "plain"))
        val parts = attachmentParts(deep)
        assertEquals(1, parts.size)
        assertEquals("multipart", parts[0].type)
    }

    @Test(timeout = 30_000)
    fun nestedLeafPartsListsEveryLeafOfAnOpenedSubtree() {
        val text = MimePart("1.1", "text", "plain", "", null, 1, emptyList())
        val pdf = MimePart("1.2", "application", "pdf", "attachment", "a.pdf", 9, emptyList())
        val more = MimePart("1.3", "application", TooDeepSubtype, "attachment", "nested part", 0, emptyList())
        val leaves = nestedLeafParts(MimePart("1", "multipart", "mixed", "", null, 0, listOf(text, pdf, more)))
        assertEquals(listOf("1.1", "1.2", "1.3"), leaves.map { it.section })
        assertEquals(1, nestedLeafParts(nested(20_000)).size)
    }

    @Test fun shallowMimeTreeStillFindsText() {
        val p = nested(10)
        assertEquals("plain", textPart(p, "plain")?.subtype)
        assertTrue(attachmentParts(p).isEmpty())
    }
}
