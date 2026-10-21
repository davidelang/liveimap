package org.dlang.liveimap.ui.about

import com.davidlang.liveimap.R
import org.dlang.liveimap.ui.help.PRIVACY_URL
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.io.File

class AboutLibraryTest {
    @Test
    fun linesAndPrivacy() {
        assertEquals(
            listOf(
                R.string.about_lib_etpan,
                R.string.about_libfastjson,
                R.string.about_openssl,
                R.string.about_cyrus_sasl,
            ),
            aboutLibraryLines(),
        )
        assertEquals("https://liveimap.lang.hm/privacy/", PRIVACY_URL)
        val xml = listOf("src/main/res/values/strings.xml", "app/src/main/res/values/strings.xml")
            .map(::File)
            .first { it.exists() }
            .readText()
        val lines = listOf(
            "about_lib_etpan",
            "about_libfastjson",
            "about_openssl",
            "about_cyrus_sasl",
        ).map { name ->
            val match = Regex("""<string name="$name">(.*?)</string>""").find(xml)
            assertNotNull(name, match)
            match!!.groupValues[1]
        }
        assertEquals(
            listOf(
                "libEtPan!, BSD 3-Clause",
                "libfastjson, MIT",
                "OpenSSL, Apache License 2.0",
                "Cyrus SASL, Carnegie Mellon University license",
            ),
            lines,
        )
        val joined = lines.joinToString("\n") + PRIVACY_URL
        assertFalse(joined.contains("PayPal"))
        assertFalse(joined.contains("Zelle"))
        assertFalse(joined.contains("Ko-fi"))
    }
}
