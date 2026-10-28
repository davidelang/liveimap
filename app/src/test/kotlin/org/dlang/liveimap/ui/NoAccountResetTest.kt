package org.dlang.liveimap.ui

import org.dlang.liveimap.settings.AccountChoice
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NoAccountResetTest {
    @Test
    fun emptyListOnly() {
        assertTrue(resetToNoAccount(emptyList()))
        assertFalse(resetToNoAccount(listOf(AccountChoice("a", "A", drawer = true))))
    }
}
