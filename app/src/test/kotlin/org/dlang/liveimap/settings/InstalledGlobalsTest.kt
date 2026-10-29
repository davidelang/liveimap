package org.dlang.liveimap.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class InstalledGlobalsTest {
    @Test
    fun onlyTheSevenSlotsAreInstalled() {
        try {
            installGlobals("indexPageSize=70\ntoolbars=abc\nsettingsOpensOn=lastEdited\nsettingsLastEditedAccount=x\nswipeThresholdPercent=15")
            assertEquals(70, SettingsHub.uiNow().indexPageSize)
            assertEquals(15, SettingsHub.uiNow().swipeThresholdPercent)
            assertEquals(listOf(
                "indexPageSize", "swipeThresholdPercent", "reconnectDelaysSec",
                "maxRawViewBytes", "maxImageTrustBytes", "moveChunk", "clientSortChunk",
            ), uiKeysInstalled())
        } finally {
            installGlobals("")
        }
    }
}
