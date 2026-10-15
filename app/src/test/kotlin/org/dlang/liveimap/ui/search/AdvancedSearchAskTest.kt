package org.dlang.liveimap.ui.search

import org.dlang.liveimap.ui.index.SearchScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdvancedSearchAskTest {
    @Test
    fun currentCountUsesTheOpenFolder() {
        assertEquals(1, bodyPromptExists(SearchScope.Current, 1, 10000))
    }

    @Test
    fun allCountUsesTheScopeSum() {
        assertEquals(10000, bodyPromptExists(SearchScope.All, 1, 10000))
    }

    @Test
    fun skipIsOnlyTheCurrentMailbox() {
        val skip = setOf("INBOX")
        assertTrue(bodyPromptSkipped(SearchScope.Current, "INBOX", skip))
        assertFalse(bodyPromptSkipped(SearchScope.All, "INBOX", skip))
    }
}
