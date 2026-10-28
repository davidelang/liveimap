package org.dlang.liveimap.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsBindingTest {
    @Test
    fun serverCapAllowsWithoutASessionOrOnJmap() {
        assertTrue(serverCapAllows(jmapFolders = false, sessionOpen = false, advertised = false))
        assertTrue(serverCapAllows(jmapFolders = true, sessionOpen = true, advertised = false))
        assertFalse(serverCapAllows(jmapFolders = false, sessionOpen = true, advertised = false))
    }
}

