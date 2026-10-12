package org.dlang.liveimap.settings

import org.dlang.liveimap.settings.FolderPlacement.AboveList
import org.dlang.liveimap.settings.FolderPlacement.Beside
import org.dlang.liveimap.settings.FolderPlacement.None
import org.dlang.liveimap.settings.PaneArrangement.SideBySide
import org.dlang.liveimap.settings.PaneArrangement.Stacked
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoPanesTest {
    private fun inch(w: Double, h: Double, font: Float = 1f) = planLayout((w * 160).toInt(), (h * 160).toInt(), font)
    private fun expect(p: LayoutPlan, panes: Int, arr: PaneArrangement, folders: FolderPlacement) {
        assertEquals("panes $p", panes, p.panes); assertEquals("arrangement $p", arr, p.arrangement)
        assertEquals("folders $p", folders, p.folders); assertEquals(panes == 1, p.readerFullScreen)
    }

    @Test fun phone() { expect(inch(3.5, 7.0), 1, SideBySide, None); expect(inch(7.0, 3.5), 3, SideBySide, Beside) }
    @Test fun squareFoldable() { expect(inch(5.2, 5.6), 2, SideBySide, None) }
    @Test fun sixInchPage() { expect(inch(3.6, 4.8), 1, SideBySide, None); expect(inch(4.8, 3.6), 2, SideBySide, None) }
    @Test fun tenInchPage() { expect(inch(6.0, 8.0), 3, SideBySide, AboveList); expect(inch(8.0, 6.0), 3, SideBySide, Beside) }
    @Test fun thirteenInchPage() { expect(inch(7.8, 10.4), 3, SideBySide, Beside); expect(inch(10.4, 7.8), 3, SideBySide, Beside) }
    @Test fun carDash() { expect(inch(10.0, 4.0), 3, SideBySide, Beside) }
    @Test fun tv32() { expect(planLayout(960, 540, 1f), 2, SideBySide, None) }  // 32 in 1080p TV reports ~960x540 dp
    @Test fun watch() { val p = planLayout(227, 227, 1f); expect(p, 1, SideBySide, None); assertEquals(PreviewSize.None, p.preview) }

    @Test fun largeFontNarrowsLayout() {
        expect(inch(8.0, 6.0, 1f), 3, SideBySide, Beside)
        expect(inch(8.0, 6.0, 1.5f), 2, SideBySide, None)
        expect(inch(8.0, 6.0, 2f), 1, SideBySide, None)
    }

    @Test fun stackedVersusNotAtEqualWidth() {
        // Same width (~94 chars): too narrow side by side; tall enough to stack only on the tall one.
        expect(planLayout(660, 1400, 1f), 2, Stacked, None)
        expect(planLayout(660, 700, 1f), 1, SideBySide, None)
        // Same width (157 chars): folders go above the list only when height allows (car-dash short cannot).
        expect(planLayout(1100, 1400, 1f), 3, SideBySide, AboveList)
        expect(planLayout(1100, 640, 1f), 2, SideBySide, None)
    }

    @Test fun listShareLeavesReaderItsMinimum() {
        val p = inch(8.0, 6.0)
        val listChars = (p.columns - FolderMinChars - GutterChars) * p.listShare
        assertTrue(listChars >= ListMinChars)
        assertTrue((p.columns - FolderMinChars - GutterChars) - listChars - GutterChars >= ReaderMinChars)
    }

    @Test fun existingAccountsKeepOffNewAccountsAuto() {
        assertEquals(MultiPane.Auto, newAccountSettings().multiPane)
        assertEquals(MultiPane.Off, AccountSettings().multiPane)
        val legacy = AccountSettings().encode()
        assertFalse(legacy.contains("multiPane"))
        assertEquals(MultiPane.Off, decodeAccountSettings(legacy).multiPane)
        val fresh = decodeAccountSettings(newAccountSettings().encode())
        assertEquals(MultiPane.Auto, fresh.multiPane)
        assertEquals(LayoutChoice.Auto, fresh.folderPane)
    }
}
