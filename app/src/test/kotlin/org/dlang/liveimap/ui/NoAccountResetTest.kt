package org.dlang.liveimap.ui

import org.dlang.liveimap.settings.AccountChoice
import org.dlang.liveimap.ui.folder.folderScreenAccountId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NoAccountResetTest {
    @Test
    fun emptyListOnly() {
        assertTrue(resetToNoAccount(emptyList()))
        assertFalse(resetToNoAccount(listOf(AccountChoice("a", "A", drawer = true))))
    }

    @Test
    fun savedAccountIdAbsentFromTheListIsDropped() {
        assertEquals("a", folderScreenAccountId("a", setOf("a", "b"), "b"))
        assertEquals("b", folderScreenAccountId("gone", setOf("b"), "b"))
        assertNull(folderScreenAccountId("gone", emptySet(), null))
        assertEquals("b", folderScreenAccountId(null, setOf("b"), "b"))
        assertNull(folderScreenAccountId(null, emptySet(), null))
    }
}
