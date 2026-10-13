package org.dlang.liveimap.settings

import org.junit.Assert.assertEquals
import org.junit.Test

/** F57: a username with spec syntax is quoted on export and unquoted on import. */
class PinercUserQuotingTest {
    @Test fun roundTrip() {
        for (u in listOf("dlang", "a/b", "x}y", "q\"uote", "back\\slash", "sp ace")) {
            val spec = "{mail.lang.hm:993/ssl/user=${quoteSpecValue(u)}}INBOX"
            val end = specEnd(spec)
            assertEquals(u, spec.length - "INBOX".length - 1, end)
            val flags = splitSpecFlags(spec.substring(1, end).substringAfter('/'))
            assertEquals(u, listOf("ssl", "user=${quoteSpecValue(u)}"), flags)
            assertEquals(u, u, unquoteSpecValue(flags[1].substring(5)))
        }
        assertEquals("dlang", quoteSpecValue("dlang"))
    }
}
