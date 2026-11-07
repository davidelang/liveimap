package org.dlang.liveimap.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DebugLoggingSettingTest {
    @Test fun offByDefaultAndPersistedInTheUiDocument() {
        installGeneratedSettings()
        assertFalse(Settings().debugLogging)
        val on = Settings().copy(debugLogging = true)
        assertTrue(decodeSettings(on.encodeUi()).debugLogging)
        assertFalse(decodeSettings(Settings().encodeUi()).debugLogging)
    }
}
