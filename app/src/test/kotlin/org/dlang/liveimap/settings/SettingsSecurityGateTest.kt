package org.dlang.liveimap.settings

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsSecurityGateTest {
    @Test
    fun unreadableSecuritySettingStopsBeforeSecrets() {
        val imap = text("kotlin/org/dlang/liveimap/engine/LibetpanMailSession.kt")
        val open = functionBody(imap, "fun open(account: AccountSettings)")
        assertBefore(open, "preservedSecurityKey", "passwordFor")
        assertTrue(open.contains("account settings need attention:"))
        val send = functionBody(imap, "fun smtpSendFrom")
        assertBefore(send, "preservedSecurityKey", "nativeSmtp")
        assertTrue(send.contains("account settings need attention:"))
        val jmap = text("kotlin/org/dlang/liveimap/jmap/JmapMailSession.kt")
        val jopen = functionBody(jmap, "fun open(account: AccountSettings)")
        assertBefore(jopen, "preservedSecurityKey", "passwordOf")
        assertTrue(jopen.contains("account settings need attention:"))
    }

    private fun assertBefore(body: String, earlier: String, later: String) {
        val atEarlier = body.indexOf(earlier)
        val atLater = body.indexOf(later)
        assertTrue(atEarlier >= 0 && atLater > atEarlier)
    }

    private fun text(path: String) =
        listOf("src/main/$path", "app/src/main/$path").map(::File).first { it.exists() }.readText()

    private fun functionBody(source: String, name: String): String {
        val start = source.indexOf(name)
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
