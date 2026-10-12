package org.dlang.liveimap.settings

import org.dlang.liveimap.ui.useFolderPane
import org.dlang.liveimap.ui.useMultiPane
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoPanesTest {
    @Test
    fun defaultsBySize() {
        assertEquals(1, autoPaneCount(411, 411, 6.7))      // phone portrait
        assertEquals(1, autoPaneCount(915, 411, 6.7))      // phone landscape: still a phone
        assertEquals(1, autoPaneCount(800, 800, 10.95))    // 11" tablet portrait
        assertEquals(2, autoPaneCount(1280, 800, 10.95))   // 11" tablet landscape
        assertEquals(3, autoPaneCount(1300, 975, 13.3))    // 13" tablet landscape
        assertEquals(2, autoPaneCount(975, 975, 13.3))     // 13" tablet portrait
    }

    @Test
    fun autoFollowsCountExplicitWins() {
        assertTrue(useMultiPane(MultiPane.Auto, 2))
        assertFalse(useMultiPane(MultiPane.Auto, 1))
        assertTrue(useMultiPane(MultiPane.Wide, 1))
        assertFalse(useMultiPane(MultiPane.Off, 3))
        assertTrue(useFolderPane(LayoutChoice.Auto, 3))
        assertFalse(useFolderPane(LayoutChoice.Auto, 2))
        assertFalse(useFolderPane(LayoutChoice.Off, 3))
    }

    @Test
    fun existingAccountsKeepOffNewAccountsAuto() {
        assertEquals(MultiPane.Auto, AccountSettings().multiPane)
        val legacy = AccountSettings(multiPane = MultiPane.Off, folderPane = LayoutChoice.Off).encode()
        assertFalse(legacy.contains("multiPane"))
        assertEquals(MultiPane.Off, decodeAccountSettings(legacy).multiPane)
        assertEquals(LayoutChoice.Off, decodeAccountSettings(legacy).folderPane)
        val fresh = decodeAccountSettings(AccountSettings().encode())
        assertEquals(MultiPane.Auto, fresh.multiPane)
        assertEquals(LayoutChoice.Auto, fresh.folderPane)
    }
}
