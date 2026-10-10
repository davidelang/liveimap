package org.dlang.liveimap.ui.about

import org.dlang.liveimap.ui.help.PRIVACY_URL
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class AboutLibraryTest {
    @Test
    fun linesAndPrivacy() {
        assertEquals(
            listOf(
                "libEtPan!, BSD 3-Clause",
                "libfastjson, MIT",
                "OpenSSL, Apache License 2.0",
                "Cyrus SASL, Carnegie Mellon University license",
            ),
            aboutLibraryLines(),
        )
        assertEquals("https://liveimap.lang.hm/privacy/", PRIVACY_URL)
        val joined = aboutLibraryLines().joinToString("\n") + PRIVACY_URL
        assertFalse(joined.contains("PayPal"))
        assertFalse(joined.contains("Zelle"))
        assertFalse(joined.contains("Ko-fi"))
    }
}
