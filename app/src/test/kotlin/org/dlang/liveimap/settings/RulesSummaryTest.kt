package org.dlang.liveimap.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class RulesSummaryTest {
    @Test
    fun neverBlank() {
        assertEquals("liveimap", rulesSummaryWho(Settings(username = "liveimap", imapHost = "10.2.3.133")))
        assertEquals("10.2.3.133", rulesSummaryWho(Settings(imapHost = "10.2.3.133")))
        assertEquals("?", rulesSummaryWho(Settings()))
        assertEquals(true, rulesSummaryWho(Settings(email = "a@x", username = "u")).contains("a@x"))
    }
}
