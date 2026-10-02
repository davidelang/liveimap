package org.dlang.liveimap.ui.compose

import org.dlang.liveimap.session.ComposeKind
import org.junit.Assert.assertEquals
import org.junit.Test

class SmtpAcceptFlagsTest {
    @Test
    fun smtpAcceptFlagsForEachKind() {
        assertEquals(setOf("\\Answered"), smtpAcceptFlags(ComposeKind.Reply))
        assertEquals(setOf("\\Answered"), smtpAcceptFlags(ComposeKind.ReplyAll))
        assertEquals(setOf("\$Forwarded"), smtpAcceptFlags(ComposeKind.Forward))
        assertEquals(setOf("\$Forwarded"), smtpAcceptFlags(ComposeKind.Bounce))
        assertEquals(emptySet<String>(), smtpAcceptFlags(ComposeKind.New))
        assertEquals(emptySet<String>(), smtpAcceptFlags(ComposeKind.ResumePostpone))
    }

    @Test
    fun mailboxLeafCases() {
        assertEquals("sent-mail", mailboxLeaf("INBOX.sent-mail"))
        assertEquals("Drafts", mailboxLeaf("Drafts"))
        assertEquals("Drafts", mailboxLeaf("INBOX/Archive/Drafts"))
    }
}
