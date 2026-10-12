package org.dlang.liveimap.ui.reader

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OriginalColorsTest {
    private val page = "<html><head><title>t</title></head><body style=\"color:#123456\">x</body></html>"

    @Test
    fun darkRecolorsOriginalKeepsSenderColors() {
        val dark = themedHtml(page, true, 0xFF101010.toInt(), 0xFFEEEEEE.toInt())
        assertTrue(dark.contains("background-color:#101010 !important"))
        val original = themedHtml(page, false, 0xFF101010.toInt(), 0xFFEEEEEE.toInt())
        assertFalse(original.contains("!important"))
        assertTrue(original.contains("color:#123456"))
    }

    @Test
    fun originalStillStripsMetaRefresh() {
        val p = "<head><meta http-equiv=\"refresh\" content=\"0;url=https://evil.example\"></head>x"
        assertFalse(themedHtml(p, false, 0, 0).contains("evil.example"))
    }
}
