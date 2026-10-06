package org.dlang.liveimap.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LineCommitTest {
    @Test
    fun equalTextReturnsNull() {
        assertNull(commitText("same", "same"))
    }

    @Test
    fun differentTextReturnsTheDraft() {
        assertEquals("next", commitText("next", "old"))
    }

    @Test
    fun emptyAgainstNonEmptyReturnsEmpty() {
        assertEquals("", commitText("", "kept"))
    }

    @Test
    fun port993Against143Returns993() {
        assertEquals(Integer.valueOf(993), commitPort("993", 143))
    }

    @Test
    fun lowestAndHighestPortsCommit() {
        assertEquals(Integer.valueOf(1), commitPort("1", 143))
        assertEquals(Integer.valueOf(65535), commitPort("65535", 143))
    }

    @Test
    fun invalidPortsReturnNull() {
        assertNull(commitPort("", 143))
        assertNull(commitPort("nope", 143))
        assertNull(commitPort("0", 143))
        assertNull(commitPort("-1", 143))
        assertNull(commitPort("65536", 143))
    }

    @Test
    fun samePortReturnsNull() {
        assertNull(commitPort("143", 143))
    }
}
