package org.dlang.liveimap.debug.acceptance

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** F19/F46: smoke mode and the acceptance runner cannot touch a real account from a release build or a real host. */
class AcceptanceGateTest {
    @Test fun writesNeedLabUserAndASuppliedLabHost() {
        val supplied = parseWriteHosts("lab.example, 192.0.2.7\n# comment\nsun.example")
        assertTrue(allowWriter("liveimap", "lab.example", supplied))
        assertTrue(allowWriter("liveimap", "192.0.2.7", supplied))
        assertTrue(allowWriter("liveimap", "SUN.example ", supplied))
        assertFalse(allowWriter("liveimap", "192.0.2.8", supplied))
        assertFalse(allowWriter("dlang", "lab.example", supplied))
        assertFalse(allowWriter("liveimap", "imap.gmail.com", supplied))
    }

    @Test fun noHostIsWritableUnlessSupplied() {
        // Nothing is built in: the hosts the old list carried are read-only until a run supplies them.
        for (h in listOf("lab.test", "10.0.2.2", "localhost", "127.0.0.1", "sun.lang.hm", "asgard.lang.hm")) {
            assertFalse(h, allowWriteHost(h, emptySet()))
        }
        assertTrue(parseWriteHosts("  \n# only a comment").isEmpty())
    }

    @Test fun noLabAddressIsHardCodedInTheRunner() {
        val dir = File("src/debug/kotlin/org/dlang/liveimap/debug/acceptance")
        val text = dir.listFiles()!!.filter { it.name.endsWith(".kt") }.joinToString("\n") { it.readText() }
        for (literal in listOf("\"lab.test\"", "\"10.", "\"sun", "\"127.0.0.1\"", "\"localhost\"")) {
            assertFalse(literal, text.contains(literal))
        }
    }

    @Test fun sourceGuards() {
        val section = File("src/debug/kotlin/org/dlang/liveimap/debug/acceptance/AcceptanceSection.kt").readText()
        assertFalse(section.contains("getExternalFilesDir"))
        assertTrue(File("src/main/kotlin/org/dlang/liveimap/settings/SettingsScreen.kt").readText()
            .contains("if (org.dlang.liveimap.BuildConfig.DEBUG) org.dlang.liveimap.debug.acceptance.AcceptanceSection()"))
        assertTrue(File("src/release/kotlin/org/dlang/liveimap/smoke/SmokeGate.kt").readText().contains("SmokeAllowed = false"))
        // Size work: the runner is debug-only; release has only an empty stub.
        assertFalse(File("src/main/kotlin/org/dlang/liveimap/debug/acceptance").exists())
        val stub = File("src/release/kotlin/org/dlang/liveimap/debug/acceptance/AcceptanceSection.kt").readText()
        assertFalse(stub.contains("Acceptance" + "Runner"))
    }
}
