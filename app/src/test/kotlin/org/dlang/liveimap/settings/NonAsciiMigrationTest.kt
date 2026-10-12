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
        val old = AccountSettings().encode().lines().filterNot { it.startsWith("nonAsciiDisplay=") }.joinToString("\n")
        assertEquals(NonAsciiDisplay.HighlightSuspicious, decodeAccountSettings(old).nonAsciiDisplay)
        val text = decodeAccountSettings(withExplicitNonAsciiDisplay(old)!!).encode()
        assertNull(withExplicitNonAsciiDisplay(text))
        assertEquals(NonAsciiDisplay.HighlightSuspicious, decodeAccountSettings(text).nonAsciiDisplay)
    }
}
