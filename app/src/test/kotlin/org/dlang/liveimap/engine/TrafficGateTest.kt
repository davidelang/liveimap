package org.dlang.liveimap.engine

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrafficGateTest {
    @Test
    fun recordingFollowsTheAccountNotThePreviousOne() {
        val session = text("kotlin/org/dlang/liveimap/engine/LibetpanMailSession.kt")
        val finish = functionBody(session, "finishTraffic")
        assertFalse(finish.contains("setRecording"))
        val prepare = functionBody(session, "prepareTraffic")
        val key = "if (account.logImapTraffic)"
        val at = prepare.indexOf(key)
        assertTrue(at >= 0)
        assertFalse(prepare.substring(0, at).contains("setRecording"))
        val branch = braceBlock(prepare, prepare.indexOf('{', at))
        assertTrue(branch.contains("setRecording(true)"))
        val after = prepare.substring(prepare.indexOf(branch, at) + branch.length)
        assertFalse(after.contains("setRecording"))
        val install = functionBody(text("kotlin/org/dlang/liveimap/engine/TrafficLog.kt"), "install")
        assertTrue(install.contains("recording = false"))
    }

    @Test
    fun recordingFollowsWhetherAnyAccountWantsIt() {
        val store = text("kotlin/org/dlang/liveimap/accounts/AccountStore.kt")
        for (name in listOf("save", "update", "removeAccount", "deleteAccountArtifacts")) {
            assertTrue(name, functionBody(store, name).contains("setRecording"))
        }
        val removal = functionBody(store, "deleteAccountArtifacts")
        val removed = removal.indexOf("prefs.remove")
        assertTrue(removal.indexOf("setRecording") > removed)
        val flags = functionBody(store, "logImapTrafficFlags")
        assertFalse(flags.contains("gate"))
        val session = text("kotlin/org/dlang/liveimap/engine/LibetpanMailSession.kt")
        assertFalse(functionBody(session, "finishTraffic").contains("setRecording"))
    }

    private fun text(path: String) =
        listOf("src/main/$path", "app/src/main/$path").map(::File).first { it.exists() }.readText()

    private fun functionBody(source: String, name: String): String {
        val start = source.indexOf("fun $name")
        check(start >= 0) { name }
        return braceBlock(source, source.indexOf('{', start))
    }

    private fun braceBlock(source: String, open: Int): String {
        var depth = 0
        var i = open
        while (i < source.length) {
            when (source[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(open, i + 1)
                }
            }
            i++
        }
        error("unclosed")
    }
}
