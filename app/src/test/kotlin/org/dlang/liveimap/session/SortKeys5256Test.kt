package org.dlang.liveimap.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** F108: the client sort fallback uses RFC 5256 keys. These cases run under [ClientSortCollation.Plain]. */
class SortKeys5256Test {
    private val collation = ClientSortCollation.Plain
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

    @Test
    fun plainKeepsTodaysOrder() {
        assertEquals(ClientSortCollation.Plain, collation)
        assertEquals(ClientSortCollation.Locale, ClientSortCollation.values().first())
        assertEquals("hello", plainKeyBytes(baseSubject("Re: Hello")).decodeToString())
        assertEquals(
            plainKeyBytes(baseSubject("Re: Hello")).decodeToString(),
            plainKeyBytes(baseSubject("RE: [list] Fwd: re[2]: Hello (fwd)")).decodeToString(),
        )
        assertEquals("alpha", plainKeyBytes("Alpha").decodeToString())
        assertEquals("é", plainKeyBytes("É").decodeToString())
        val keys = listOf("beta", "Alpha").map { plainKeyBytes(it) }
        assertTrue(memcmp(keys[1], keys[0]) < 0)
    }

    private fun memcmp(a: ByteArray, b: ByteArray): Int {
        val n = minOf(a.size, b.size)
        for (i in 0 until n) {
            val c = (a[i].toInt() and 0xff) - (b[i].toInt() and 0xff)
            if (c != 0) return c
        }
        return a.size - b.size
    }
}
