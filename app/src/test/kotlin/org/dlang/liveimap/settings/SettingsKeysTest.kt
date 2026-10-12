package org.dlang.liveimap.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsKeysTest {
    @Test fun enterCommitsOnDownAndSwallowsUp() {
        assertEquals(LineKey.Commit, lineFieldKey(isEnter = true, keyDown = true, singleLine = true))
        assertEquals(LineKey.Swallow, lineFieldKey(isEnter = true, keyDown = false, singleLine = true))
    }

    @Test fun otherKeysAndMultiLinePass() {
        assertEquals(LineKey.Pass, lineFieldKey(isEnter = false, keyDown = true, singleLine = true))
        assertEquals(LineKey.Pass, lineFieldKey(isEnter = true, keyDown = true, singleLine = false))
    }
}
