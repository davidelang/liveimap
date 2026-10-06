package org.dlang.liveimap.ui.compose

import org.dlang.liveimap.session.ComposeKind
import org.dlang.liveimap.ui.foldReaderIntoIndex
import org.dlang.liveimap.ui.postponedDrawerMailbox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ComposeChromeTest {
    @Test
    fun composeIsDirty() {
        assertFalse(
            composeIsDirty(
                to = "ann@example.com",
                cc = "bo@example.com",
                bcc = "cy@example.com",
                subject = "Hello",
                body = "Body",
                baselineTo = "ann@example.com",
                baselineCc = "bo@example.com",
                baselineBcc = "cy@example.com",
                baselineSubject = "Hello",
                baselineBody = "Body",
                rowRemoved = false,
            ),
        )
        assertTrue(
            composeIsDirty(
                to = "ann@example.com",
                cc = "bo@example.com",
                bcc = "cy@example.com",
                subject = "Changed",
                body = "Body",
                baselineTo = "ann@example.com",
                baselineCc = "bo@example.com",
                baselineBcc = "cy@example.com",
                baselineSubject = "Hello",
                baselineBody = "Body",
                rowRemoved = false,
            ),
        )
        assertTrue(
            composeIsDirty(
                to = "ann@example.com",
                cc = "bo@example.com",
                bcc = "cy@example.com",
                subject = "Hello",
                body = "Body",
                baselineTo = "ann@example.com",
                baselineCc = "bo@example.com",
                baselineBcc = "cy@example.com",
                baselineSubject = "Hello",
                baselineBody = "Body",
                rowRemoved = true,
            ),
        )
    }

    @Test
    fun foldReaderIntoIndex() {
        assertTrue(foldReaderIntoIndex(true, "reader/{mailbox}/{uid}/{sequence}"))
        assertFalse(foldReaderIntoIndex(false, "reader/{mailbox}/{uid}/{sequence}"))
        assertFalse(foldReaderIntoIndex(true, "index/{mailbox}"))
        assertFalse(foldReaderIntoIndex(true, "folders"))
        assertFalse(foldReaderIntoIndex(true, null))
    }

    @Test
    fun postponedDrawerMailbox() {
        assertNull(postponedDrawerMailbox(""))
        assertEquals("INBOX.postponed", postponedDrawerMailbox("INBOX.postponed")!!)
    }

    @Test
    fun unsentSubjectAndLabel() {
        assertEquals("Hello", unsentSubject("Subject: Hello\r\n\r\nBody".encodeToByteArray(), "No subject"))
        assertEquals(
            "No subject",
            unsentSubject("From: a@example.com\r\n\r\nHi".encodeToByteArray(), "No subject"),
        )
    }

    @Test
    fun postponedUidToRemove() {
        assertEquals(8L, postponedUidToRemove(ComposeKind.ResumePostpone, true, 8L)!!)
        assertNull(postponedUidToRemove(ComposeKind.ResumePostpone, false, 8L))
        assertNull(postponedUidToRemove(ComposeKind.Reply, true, 8L))
    }
}
