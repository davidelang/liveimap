package org.dlang.liveimap.debug.acceptance

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** F19/F46: smoke mode and the acceptance runner cannot touch a real account from a release build or a real host. */
class AcceptanceGateTest {
    @Test fun writesNeedLabUserAndLabHost() {
        assertTrue(allowWriter("liveimap", "lab.test"))
        assertTrue(allowWriter("liveimap", "sun.lang.hm"))
        assertFalse(allowWriter("liveimap", "asgard.lang.hm"))
        assertFalse(allowWriter("liveimap", "moon.lang.hm"))
        assertFalse(allowWriter("dlang", "lab.test"))
        assertFalse(allowWriter("liveimap", "imap.gmail.com"))
    }

    @Test fun sourceGuards() {
        val section = File("src/main/kotlin/org/dlang/liveimap/debug/acceptance/AcceptanceSection.kt").readText()
        assertFalse(section.contains("getExternalFilesDir"))
        assertTrue(File("src/main/kotlin/org/dlang/liveimap/settings/SettingsScreen.kt").readText()
            .contains("if (org.dlang.liveimap.BuildConfig.DEBUG) org.dlang.liveimap.debug.acceptance.AcceptanceSection()"))
        assertTrue(File("src/release/kotlin/org/dlang/liveimap/smoke/SmokeGate.kt").readText().contains("SmokeAllowed = false"))
    }
}
