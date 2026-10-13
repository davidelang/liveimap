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
        // F137
        assertEquals("jd", firstAddrMailbox("\"Doe, John\" <jd@x.org>, other@y"))
        assertEquals("jd", firstAddrMailbox("=?utf-8?q?Doe=2C_John?= <jd@x>"))
        assertEquals("jd", firstAddrMailbox("Doe (Smith, J) <jd@x>"))
        assertEquals("a", firstAddrMailbox("Friends: a@b, c@d;, e@f"))
        assertEquals("jd", firstAddrMailbox("<@relay.example,@r2:jd@x>"))
        assertEquals("first.last", firstAddrMailbox("\"First.Last\"@x (c)"))
        assertEquals("", firstAddrMailbox("Undisclosed recipients:;"))
    }

    @Test
    fun dateKeyComesFromTheDateHeader() {
        assertEquals(0L, dateHeaderEpoch("Thu, 1 Jan 1970 00:00:00 +0000"))
        assertEquals(3600L, dateHeaderEpoch("1 Jan 1970 00:00 -0100 (comment)"))
        // F138: obsolete zones and years
        assertEquals(0L, dateHeaderEpoch("Thu, 1 Jan 1970 00:00:00 GMT"))
        assertEquals(0L, dateHeaderEpoch("1 Jan 1970 00:00 UT"))
        assertEquals(5 * 3600L, dateHeaderEpoch("1 Jan 1970 00:00 EST"))
        assertEquals(7 * 3600L, dateHeaderEpoch("Thu, 1 Jan 1970 00:00:00 PDT"))
        assertEquals(0L, dateHeaderEpoch("1 Jan 70 00:00 +0000"))
        assertEquals(dateHeaderEpoch("1 Jan 2003 00:00 +0000"), dateHeaderEpoch("1 Jan 03 00:00 +0000"))
        assertEquals(dateHeaderEpoch("1 Jan 2003 00:00 +0000"), dateHeaderEpoch("1 Jan 103 00:00 +0000"))
        assertEquals(0L, dateHeaderEpoch("1 Jan 1970 00:00:00 A"))
        assertNull(dateHeaderEpoch("not a date"))
        assertNull(dateHeaderEpoch(""))
    }
}
