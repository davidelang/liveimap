package org.dlang.liveimap.ui.reader

import org.dlang.liveimap.settings.SaveNameRule
import org.junit.Assert.assertEquals
import org.junit.Test

/** F60: names derived from sender-controlled headers keep only [a-z0-9._-]. */
class SaveNameSafetyTest {
    private fun byFrom(from: String) = saveFolderName(SaveNameRule.ByFrom, "saved-messages", "", from, "", "", "")

    @Test fun hostileLocalPartsAreSanitised() {
        assertEquals("trash.inbox", byFrom("\"x\" <trash/../INBOX@evil>"))
        assertEquals("ax1login", byFrom("<a\r\nX1 LOGIN@evil>"))
        assertEquals("ab", byFrom("<..a*b@evil>"))
        assertEquals("saved-messages", byFrom("<\r\n@evil>"))
        assertEquals("david.lang", byFrom("David <David.Lang@lang.hm>"))
    }
}
