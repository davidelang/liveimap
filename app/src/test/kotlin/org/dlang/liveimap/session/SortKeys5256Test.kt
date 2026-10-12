package org.dlang.liveimap.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** F108: the client sort fallback uses RFC 5256 keys. */
class SortKeys5256Test {
    @Test
    fun baseSubjectStripsReplyForwardAndBlobs() {
        assertEquals("hello", baseSubject("Re: Hello"))
        assertEquals("hello", baseSubject("RE: [list] Fwd: re[2]: Hello (fwd)"))
        assertEquals("hello world", baseSubject("[Fwd: Hello   world]"))
        assertEquals("[only blob]", baseSubject("[only blob]"))
        assertEquals("", baseSubject("Re:"))
    }

    @Test
    fun addressKeyIsFirstMailboxLocalPart() {
        assertEquals("alice", firstAddrMailbox("\"Zed\" <Alice@Example.org>, bob@x"))
        assertEquals("bob", firstAddrMailbox("bob@x"))
    }

    @Test
    fun dateKeyComesFromTheDateHeader() {
        assertEquals(0L, dateHeaderEpoch("Thu, 1 Jan 1970 00:00:00 +0000"))
        assertEquals(3600L, dateHeaderEpoch("1 Jan 1970 00:00 -0100 (comment)"))
        assertNull(dateHeaderEpoch("not a date"))
        assertNull(dateHeaderEpoch(""))
    }
}
