package org.dlang.liveimap.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class RulesSummaryTest {
    @Test
    fun neverBlank() {
        assertEquals("liveimap", rulesSummaryWho(AccountSettings(username = "liveimap", imapHost = "10.2.3.133")))
        assertEquals("10.2.3.133", rulesSummaryWho(AccountSettings(imapHost = "10.2.3.133")))
        assertEquals("?", rulesSummaryWho(AccountSettings()))
        assertEquals(true, rulesSummaryWho(AccountSettings(email = "a@x", username = "u")).contains("a@x"))
    }
}
