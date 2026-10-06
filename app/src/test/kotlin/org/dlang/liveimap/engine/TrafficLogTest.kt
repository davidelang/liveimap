package org.dlang.liveimap.engine

import java.io.File
import org.dlang.liveimap.settings.AccountSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrafficLogTest {
    @Test
    fun passwordBytesAreRedacted() {
        val file = tempLog()
        val log = TrafficLog(file, clock = { 1_700_000_000_000L })
        val secret = "s3cret-password"
        log.accept("main", TrafficLog.LOG_PRIVATE, secret.toByteArray(Charsets.US_ASCII))
        val text = file.readText()
        assertTrue(text.contains("<redacted ${secret.length} bytes>"))
        assertFalse(text.contains(secret))
        assertTrue(text.startsWith("1700000000000 main "))
    }

    @Test
    fun loginPasswordIsRedacted() {
        val file = tempLog()
        val log = TrafficLog(file, clock = { 5L })
        val secret = "hunter2secret"
        log.accept("main", TrafficLog.LOG_SENT, "A1 LOGIN ada $secret\r\n".toByteArray(Charsets.US_ASCII))
        val text = file.readText()
        assertFalse(text.contains(secret))
        assertTrue(text.contains("LOGIN ada <redacted ${secret.length} bytes>"))
    }

    @Test
    fun authenticateContinuationIsRedacted() {
        val file = tempLog()
        val log = TrafficLog(file, clock = { 5L })
        val secret = "AGFkbWluAHNlY3JldA=="
        log.accept("main", TrafficLog.LOG_SENT, "A1 AUTHENTICATE PLAIN\r\n".toByteArray(Charsets.US_ASCII))
        log.accept("main", TrafficLog.LOG_RECEIVED, "+\r\n".toByteArray(Charsets.US_ASCII))
        log.accept("main", TrafficLog.LOG_SENT, "$secret\r\n".toByteArray(Charsets.US_ASCII))
        val text = file.readText()
        assertFalse(text.contains(secret))
        assertTrue(text.contains("<redacted ${secret.length} bytes>"))
        assertTrue(text.contains("AUTHENTICATE PLAIN"))
    }

    @Test
    fun literalBytesAreAbsent() {
        val file = tempLog()
        val log = TrafficLog(file, clock = { 9L })
        val body = "HelloBody!!"
        log.accept("main", TrafficLog.LOG_SENT, "A1 APPEND INBOX {11}\r\nHe".toByteArray(Charsets.US_ASCII))
        log.accept("main", TrafficLog.LOG_SENT, "lloBody!!".toByteArray(Charsets.US_ASCII))
        val text = file.readText()
        assertEquals(11, body.length)
        assertTrue(text.contains("<literal 11 bytes>"))
        assertFalse(text.contains(body))
        assertFalse(text.contains("Hello"))
        assertTrue(text.contains("APPEND INBOX {11}"))
    }

    @Test
    fun identicalRepliesCollapse() {
        val file = tempLog()
        var tick = 10L
        val log = TrafficLog(file, clock = { tick++ })
        val line = "* 1 EXISTS\r\n"
        log.accept("watch", TrafficLog.LOG_RECEIVED, (line + line).toByteArray(Charsets.US_ASCII))
        val stored = file.readText().lines().filter { it.isNotEmpty() }
        assertEquals(1, stored.size)
        assertTrue(stored[0].contains(" watch "))
        assertTrue(stored[0].contains("S * 1 EXISTS"))
        assertTrue(stored[0].contains("(repeated 2 times)"))
        assertTrue(stored[0].startsWith("10 "))
    }

    @Test
    fun capDropsOldestLine() {
        val file = tempLog()
        val log = TrafficLog(file, clock = { 3L })
        val cap = TrafficLog.MAX_BYTES
        log.accept("main", TrafficLog.LOG_RECEIVED, "FIRST-LINE-MARKER\r\n".toByteArray(Charsets.US_ASCII))
        val filler = "x".repeat(8000)
        var fed = "FIRST-LINE-MARKER\r\n".length
        var n = 0
        while (fed <= cap) {
            val wire = "L$n $filler\r\n"
            log.accept("main", TrafficLog.LOG_RECEIVED, wire.toByteArray(Charsets.US_ASCII))
            fed += wire.length
            n += 1
        }
        val text = file.readText()
        assertTrue(file.length() <= cap)
        assertFalse(text.contains("FIRST-LINE-MARKER"))
        assertTrue(text.contains("L${n - 1} "))
        assertFalse(File(file.parentFile, file.name + ".1").exists())
    }

    @Test
    fun reportHidesUserUnlessAsked() {
        val file = tempLog()
        val log = TrafficLog(file, clock = { 1L })
        log.noteCapability("IMAP4rev1 NAMESPACE")
        log.noteStatus("Selected INBOX")
        val hidden = AccountSettings(username = "ada.user", imapHost = "imap.example.com")
        val report = log.debugReport("v0.test", 1, "14", "pixel", hidden)
        assertTrue(report.contains("LiveIMAP v0.test (1)"))
        assertTrue(report.contains("Android 14 pixel"))
        assertTrue(report.contains("username=<user>"))
        assertFalse(report.contains("ada.user"))
        assertTrue(report.contains("IMAP4rev1 NAMESPACE"))
        assertTrue(report.contains("Selected INBOX"))
        val shown = log.debugReport(
            "v0.test",
            1,
            "14",
            "pixel",
            hidden.copy(showUserInDebugReport = true),
        )
        assertTrue(shown.contains("username=ada.user"))
    }

    @Test
    fun reportStripsPlantedSecrets() {
        val file = tempLog()
        val log = TrafficLog(file, clock = { 1L })
        val password = "hunter2secret"
        val token = "ya29.SUPERTOKEN"
        val sasl = "AGFkbWluAHNlY3JldA=="
        file.writeText(
            listOf(
                "1 main C A1 LOGIN ada $password",
                "1 main token=$token",
                "1 main C $sasl",
                "1 main password=$password",
            ).joinToString("\n", postfix = "\n"),
        )
        val report = log.debugReport("v", 1, "14", "pixel", AccountSettings(username = "ada.user"))
        assertFalse(report.contains(password))
        assertFalse(report.contains(token))
        assertFalse(report.contains(sasl))
        assertTrue(report.contains("username=<user>"))
    }

    @Test
    fun loggingOffStoresNothing() {
        val file = tempLog()
        val log = TrafficLog(file, recording = false)
        log.accept("main", TrafficLog.LOG_RECEIVED, "* OK ready\r\n".toByteArray(Charsets.US_ASCII))
        log.accept("main", TrafficLog.LOG_PRIVATE, "secret".toByteArray(Charsets.US_ASCII))
        log.note("main", "parsed 1")
        log.flush()
        assertFalse(file.exists())
    }

    private fun tempLog(): File {
        val file = File.createTempFile("imap-traffic", ".log")
        file.delete()
        return file
    }
}
