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
    private fun parsed(headers: String) =
        org.dlang.liveimap.ui.compose.parseRfc822("$headers\r\nSubject: x\r\n\r\nbody".toByteArray())

    @Test fun listSuppliedFolderNameRefused() {
        // A name a shared-namespace user created with CRLF (arrives as a LIST literal) is refused on reuse.
        assertTrue(runCatching { requireWireText("Shared/x\r\nA9 DELETE INBOX") }.isFailure)
    }

    @Test fun saveByFromNameWithCrLfRefused() {
        val m = parsed("From: =?utf-8?q?a=0D=0AA9_DELETE_INBOX?= <=?utf-8?q?x=0D=0AA9_DELETE_INBOX?=@evil>")
        val from = org.dlang.liveimap.ui.compose.headerValues(m, "From").first()
        val name = org.dlang.liveimap.ui.reader.saveFolderName(
            org.dlang.liveimap.settings.SaveNameRule.ByFrom, "saved", "", from, "", "", "",
        )
        // The derived name carries the decoded CR/LF; COPY goes through wire(), which refuses it.
        assertFalse(name, wireTextOk(name))
        assertTrue(runCatching { requireWireText(name) }.isFailure)
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

    @Test fun listPostCrLfNeverReachesRcpt() {
        val m = parsed("List-Post: <mailto:list@x.example%0D%0ARCPT%20TO:%3Cspy@evil%3E>")
        // F59: rejected outright now (null), so nothing reaches RCPT TO
        assertEquals(null, org.dlang.liveimap.ui.compose.listPostAddress(m))
    }
}
