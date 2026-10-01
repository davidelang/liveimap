package org.dlang.liveimap.ui.index

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IndexAppearanceTest {
    @Test
    fun unreadReadAndDeleted() {
        val unread = indexAppearance(emptySet())
        assertEquals(1f, unread.alpha, 0f)
        assertFalse(unread.strikethrough)

        val read = indexAppearance(setOf("\\Seen"))
        assertEquals(0.55f, read.alpha, 0f)
        assertFalse(read.strikethrough)

        val deleted = indexAppearance(setOf("\\Deleted"))
        assertEquals(1f, deleted.alpha, 0f)
        assertTrue(deleted.strikethrough)

        val readDeleted = indexAppearance(setOf("\\Seen", "\\Deleted"))
        assertEquals(0.55f, readDeleted.alpha, 0f)
        assertTrue(readDeleted.strikethrough)
    }
}
