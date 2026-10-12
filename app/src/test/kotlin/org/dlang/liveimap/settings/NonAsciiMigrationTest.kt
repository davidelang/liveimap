package org.dlang.liveimap.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NonAsciiMigrationTest {
    @Test fun missingLineGetsDefaultExplicitly() {
        val old = "imapHost=h\nusername=u\n"
        val next = withExplicitNonAsciiDisplay(old)!!
        assertEquals(old + "nonAsciiDisplay=HighlightSuspicious\n", next)
        assertNull(withExplicitNonAsciiDisplay(next))
    }

    @Test fun existingChoiceIsKept() {
        assertNull(withExplicitNonAsciiDisplay("imapHost=h\nnonAsciiDisplay=Normal\n"))
    }

    @Test fun encodeAlwaysWritesIt() {
        val text = decodeAccountSettings(withExplicitNonAsciiDisplay("imapHost=h")!!).encode()
        assertNull(withExplicitNonAsciiDisplay(text))
        assertEquals(NonAsciiDisplay.HighlightSuspicious, decodeAccountSettings(text).nonAsciiDisplay)
    }
}
