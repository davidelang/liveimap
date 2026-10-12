package org.dlang.liveimap.settings

import org.dlang.liveimap.ui.PaneAxis
import org.dlang.liveimap.ui.paneAxis
import org.dlang.liveimap.ui.useFolderPane
import org.dlang.liveimap.ui.useMultiPane
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoPanesTest {
    private fun dp(inches: Double) = (inches * 160).toInt()
    private fun check(wIn: Double, hIn: Double, screen: ScreenClass, panes: Int, arr: PaneArrangement = PaneArrangement.SideBySide) {
        val d = classifyScreen(dp(wIn), dp(hIn))
        assertEquals("$wIn x $hIn", screen, d.screen)
        assertEquals("$wIn x $hIn panes", panes, d.panes)
        assertEquals("$wIn x $hIn arrangement", arr, d.arrangement)
    }

    @Test
    fun classesPortraitAndLandscape() {
        check(3.5, 7.0, ScreenClass.NarrowPhone, 1); check(7.0, 3.5, ScreenClass.NarrowPhone, 1)
        check(5.2, 5.6, ScreenClass.FoldableSquare, 2); check(5.6, 5.2, ScreenClass.FoldableSquare, 2) // ~7.6 in
        check(3.6, 4.8, ScreenClass.SmallPage, 1); check(4.8, 3.6, ScreenClass.SmallPage, 1)          // 6 in
        check(6.0, 8.0, ScreenClass.Page10, 1); check(8.0, 6.0, ScreenClass.Page10, 2)                // 10 in
        check(8.0, 10.6, ScreenClass.Page13, 2, PaneArrangement.TopBottom); check(10.6, 8.0, ScreenClass.Page13, 3) // 13.3 in
    }

    @Test
    fun foldUnfoldChangesClass() {
        assertEquals(ScreenClass.NarrowPhone, classifyScreen(dp(2.6), dp(6.0)).screen) // folded cover screen
        assertEquals(ScreenClass.FoldableSquare, classifyScreen(dp(5.0), dp(5.4)).screen)
    }

    @Test
    fun autoFollowsDefaultExplicitWins() {
        assertTrue(useMultiPane(MultiPane.Auto, 2)); assertFalse(useMultiPane(MultiPane.Auto, 1))
        assertTrue(useMultiPane(MultiPane.Wide, 1)); assertFalse(useMultiPane(MultiPane.Off, 3))
        assertTrue(useFolderPane(LayoutChoice.Auto, 3)); assertFalse(useFolderPane(LayoutChoice.Auto, 2))
        assertFalse(useFolderPane(LayoutChoice.Off, 3))
        assertEquals(PaneAxis.TopBottom, paneAxis(false, false, false, autoTopBottom = true))
        assertEquals(PaneAxis.SideBySide, paneAxis(false, false, false, autoTopBottom = false))
    }

    @Test
    fun existingAccountsKeepOffNewAccountsAuto() {
        assertEquals(MultiPane.Auto, newAccountSettings().multiPane)
        assertEquals(MultiPane.Off, AccountSettings().multiPane)
        val legacy = AccountSettings().encode()
        assertFalse(legacy.contains("multiPane"))
        assertEquals(MultiPane.Off, decodeAccountSettings(legacy).multiPane)
        assertEquals(LayoutChoice.Off, decodeAccountSettings(legacy).folderPane)
        val fresh = decodeAccountSettings(newAccountSettings().encode())
        assertEquals(MultiPane.Auto, fresh.multiPane)
        assertEquals(LayoutChoice.Auto, fresh.folderPane)
    }
}
