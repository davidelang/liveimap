package org.dlang.liveimap.ui

import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.style.TextDecoration
import org.dlang.liveimap.settings.NonAsciiDisplay
import org.dlang.liveimap.settings.decodeAccountSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NonAsciiTextTest {
    private val hl = SpanStyle(textDecoration = TextDecoration.Underline)

    @Test fun cyrillicInsideLatinWordIsSuspicious() {
        val s = "Your p\u0430ypal account" // Cyrillic а
        assertEquals(listOf(5 until 11), NonAsciiText.suspiciousRanges(s))
    }

    @Test fun wholeCyrillicLookalikeWordNextToLatin() {
        assertTrue(NonAsciiText.isSuspicious("Sign in to \u0430\u0440\u0440\u04CF\u0435 now")) // аррӏе
    }

    @Test fun ordinaryAccentsAndScriptsAreNot() {
        listOf("Café", "Grüße aus Köln", "Привет, мир", "日本語のメール", "Re: résumé — naïve").forEach {
            assertFalse(it, NonAsciiText.isSuspicious(it))
        }
    }

    @Test fun invisibleAndBidiControls() {
        assertTrue(NonAsciiText.isSuspicious("pay\u200Bpal"))
        assertTrue(NonAsciiText.isSuspicious("invoice\u202Efdp.exe"))
    }

    @Test fun fullWidthMixedWithLatin() {
        assertTrue(NonAsciiText.isSuspicious("pay\uFF50al"))
    }

    @Test fun idnDomainsInAddresses() {
        assertEquals(1, NonAsciiText.idnDomainRanges("Bank <help@xn--pple-43d.com>").size)
        assertEquals(1, NonAsciiText.idnDomainRanges("help@bücher.de").size)
        assertTrue(NonAsciiText.idnDomainRanges("help@example.com").isEmpty())
    }

    @Test fun modes() {
        val s = "p\u0430ypal"
        assertEquals(s, NonAsciiText.styled(s, NonAsciiDisplay.Normal, hl).text)
        assertTrue(NonAsciiText.styled(s, NonAsciiDisplay.Normal, hl).spanStyles.isEmpty())
        assertEquals(1, NonAsciiText.styled(s, NonAsciiDisplay.HighlightSuspicious, hl).spanStyles.size)
        assertEquals("p\\u{0430}ypal", NonAsciiText.styled(s, NonAsciiDisplay.Escape, hl).text)
        assertTrue(NonAsciiText.styled(s, NonAsciiDisplay.Warn, hl).text.startsWith(NonAsciiText.WarnMark))
        assertEquals("Café", NonAsciiText.styled("Café", NonAsciiDisplay.Warn, hl).text)
        assertTrue(NonAsciiText.styled("Café", NonAsciiDisplay.HighlightSuspicious, hl).spanStyles.isEmpty())
        assertEquals(1, NonAsciiText.styled("Café", NonAsciiDisplay.HighlightAll, hl).spanStyles.size)
    }

    @Test fun storedAccountsDefaultToHighlightSuspicious() {
        assertEquals(NonAsciiDisplay.HighlightSuspicious, decodeAccountSettings("username=a\n").nonAsciiDisplay)
    }
}
