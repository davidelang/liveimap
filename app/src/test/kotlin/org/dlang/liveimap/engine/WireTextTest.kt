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

class WireTextPathsTest {
    @Test fun listSuppliedFolderNameRefused() {
        // A name a shared-namespace user created with CRLF (arrives as a LIST literal) is refused on reuse.
        assertTrue(runCatching { requireWireText("Shared/x\r\nA9 DELETE INBOX") }.isFailure)
    }

    @Test fun keywordsMustBeAtoms() {
        assertTrue(runCatching { requireFlags(listOf("ok", "bad word")) }.isFailure)
        assertTrue(runCatching { requireFlags(listOf("x\r\nA9 LOGOUT")) }.isFailure)
        assertTrue(runCatching { requireSearchArgument("NotKeyword", "a)b") }.isFailure)
    }

    @Test fun savedAndAdvancedSearchBareCrRefused() {
        val q = org.dlang.liveimap.ui.index.encodeAdvancedQuery(
            org.dlang.liveimap.ui.index.AdvancedCombiner.And,
            listOf(org.dlang.liveimap.ui.index.AdvancedStep(false, "Subject", "a\rA9 LOGOUT")),
        )
        assertEquals(null, q) // F118: the builder itself refuses a bare CR now
        assertTrue(runCatching { requireSearchArgument("Advanced", "And\nYes\tSubject\ta\rA9 LOGOUT") }.isFailure)
        assertTrue(runCatching { requireSearchArgument("Subject", "a\rb") }.isFailure)
        assertTrue(runCatching { requireCriteria("SUBJECT \"a\rb\"") }.isFailure)
        val ok = org.dlang.liveimap.ui.index.encodeAdvancedQuery(
            org.dlang.liveimap.ui.index.AdvancedCombiner.And,
            listOf(org.dlang.liveimap.ui.index.AdvancedStep(false, "Subject", "fine")),
        )!!
        assertEquals(ok, requireSearchArgument("Advanced", ok))
    }

    @Test fun filterChainGroupKeepsNewlinesAndRawBreaksThrow() {
        val fromOr = org.dlang.liveimap.ui.index.encodeAdvancedQuery(
            org.dlang.liveimap.ui.index.AdvancedCombiner.Or,
            listOf(
                org.dlang.liveimap.ui.index.AdvancedStep(false, "From", "a"),
                org.dlang.liveimap.ui.index.AdvancedStep(false, "From", "b"),
            ),
        )!!
        val subjectOr = org.dlang.liveimap.ui.index.encodeAdvancedQuery(
            org.dlang.liveimap.ui.index.AdvancedCombiner.Or,
            listOf(
                org.dlang.liveimap.ui.index.AdvancedStep(false, "Subject", "c"),
                org.dlang.liveimap.ui.index.AdvancedStep(false, "Subject", "d"),
            ),
        )!!
        val chain = org.dlang.liveimap.ui.index.filterChainGroup(
            listOf(
                org.dlang.liveimap.ui.index.AppliedFilter("A", fromOr, "Advanced"),
                org.dlang.liveimap.ui.index.AppliedFilter("B", subjectOr, "Advanced"),
            ),
        )!!
        assertEquals(chain, requireCriteria(chain))
        assertTrue(chain.contains("\n"))
        assertEquals(2, chain.split("\tGroup\t").size - 1)
        assertTrue(runCatching { requireCriteria("SUBJECT \"a\nb\"") }.isFailure)
        assertTrue(runCatching { requireCriteria("And\nYes\tSubject\ta\rX") }.isFailure)
    }
}
