package org.dlang.liveimap.engine

import org.dlang.liveimap.session.MailFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WireTextTest {
    @Test fun lineBreaksRefused() {
        assertTrue(wireTextOk("Shared/Ann's folder ünï"))
        assertFalse(wireTextOk("x\r\nA1 DELETE INBOX"))
        assertFalse(wireTextOk("x\ny"))
        assertFalse(wireTextOk("x\ry"))
    }

    @Test(expected = MailFailure::class) fun mailboxWithCrLfThrows() {
        requireWireText("evil\r\nA2 DELETE INBOX")
    }

    @Test fun keywords() {
        assertTrue(flagAtomOk("\\Seen"))
        assertTrue(flagAtomOk("\$Junk"))
        assertFalse(flagAtomOk("two words"))
        assertFalse(flagAtomOk("x)"))
        assertFalse(flagAtomOk("a\r\nb"))
        assertFalse(flagAtomOk(""))
    }

    @Test fun searchArguments() {
        assertEquals("hello world", requireSearchArgument("Text", "hello world"))
        assertTrue(runCatching { requireSearchArgument("Keyword", "a b") }.isFailure)
        assertTrue(runCatching { requireSearchArgument("Text", "a\r\nb") }.isFailure)
    }
}
