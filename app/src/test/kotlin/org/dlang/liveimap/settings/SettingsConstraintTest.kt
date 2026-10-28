package org.dlang.liveimap.settings

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsConstraintTest {
    @Test
    fun numbersUseLeafMinAndMax() {
        val tree = settingsTreeText()
        assertNotNull(rejectedNumber(tree, "imapPort", 70000))
        assertNull(rejectedNumber(tree, "imapPort", 993))
        assertNotNull(rejectedNumber(tree, "maxConnections", 1))
        assertNull(rejectedNumber(tree, "maxConnections", 8))
        val fresh = rejectedNumber(tree, "folderCountFreshSeconds", 0)
        assertNotNull(fresh)
        assertTrue(fresh!!.contains("folderCountFreshSeconds"))
        assertNull(rejectedNumber(tree, "androidContactsCacheSeconds", 0))
    }
}
