package org.dlang.liveimap.settings

import org.dlang.liveimap.settings.editors.openingAccount
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SettingsEditorsTest {
    @Test
    fun openingAccountFollowsThePageRule() {
        assertNull(openingAccount(emptyList(), "drawer", ""))
        val only = AccountChoice("a", "Ada", drawer = true)
        assertEquals(only, openingAccount(listOf(only), "lastEdited", "missing"))
        val first = AccountChoice("a", "Ada", drawer = false)
        val second = AccountChoice("b", "Bea", drawer = true)
        val both = listOf(first, second)
        assertEquals(second, openingAccount(both, "drawer", "a"))
        assertEquals(first, openingAccount(both, "lastEdited", "a"))
        assertEquals(second, openingAccount(both, "lastEdited", ""))
        assertEquals(second, openingAccount(both, "lastEdited", "gone"))
    }
}
