package org.dlang.liveimap.ui.index

import org.dlang.liveimap.settings.Settings
import org.dlang.liveimap.settings.Density
import org.dlang.liveimap.settings.FolderPlace
import org.dlang.liveimap.settings.PreviewChoice
import org.dlang.liveimap.settings.PreviewSize
import org.dlang.liveimap.settings.decodeSettings
import org.dlang.liveimap.settings.encode
import org.dlang.liveimap.settings.newAccountSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewSettingTest {
    @Test fun storedAccountsKeepDensityBehaviour() {
        val legacy = decodeSettings(Settings(density = Density.Medium, previewSize = PreviewChoice.Density).encode())
        assertEquals(PreviewChoice.Density, legacy.previewSize)
        assertEquals(FolderPlace.Auto, legacy.folderPlace)
        assertEquals(3, previewLines(legacy, PreviewSize.None))
        assertTrue(wantsPreview(legacy))
        assertFalse(wantsPreview(legacy.copy(density = Density.Compact)))
    }

    @Test fun explicitAndAuto() {
        val a = Settings()
        assertEquals(0, previewLines(a.copy(previewSize = PreviewChoice.None), PreviewSize.Large))
        assertFalse(wantsPreview(a.copy(previewSize = PreviewChoice.None, density = Density.Large)))
        assertEquals(2, previewLines(a.copy(previewSize = PreviewChoice.Small), PreviewSize.None))
        assertEquals(5, previewLines(a.copy(previewSize = PreviewChoice.Large, density = Density.Compact), PreviewSize.None))
        assertTrue(wantsPreview(a.copy(previewSize = PreviewChoice.Large, density = Density.Compact)))
        val auto = a.copy(previewSize = PreviewChoice.Auto)
        assertEquals(0, previewLines(auto, PreviewSize.None))
        assertEquals(5, previewLines(auto, PreviewSize.Large))
    }

    @Test fun newAccountsAutoRoundTrip() {
        val n = decodeSettings(newAccountSettings().encode())
        assertEquals(PreviewChoice.Auto, n.previewSize)
        assertEquals(FolderPlace.Auto, n.folderPlace)
    }
}
