package org.dlang.liveimap.engine

import java.io.File
import java.nio.file.Files
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrafficRecorderTest {
    private fun recorder(): TrafficRecorder {
        val dir = Files.createTempDirectory("rec").toFile()
        val header = TrafficRecorder.RunHeader("v1", "abc123", "app", "greenmail", "10.0.0.9", 3143, "None", "imap", "smoke")
        var t = 1000L
        return TrafficRecorder(File(dir, "r.jsonl"), header) { t++ }
    }

    private fun lines(r: TrafficRecorder) = r.file.readLines()

    @Test
    fun headerCarriesRunFields() {
        val first = lines(recorder()).first()
        assertTrue(first.startsWith("{\"type\":\"run\",\"format\":1,\"app\":\"v1\",\"commit\":\"abc123\""))
        assertTrue(first.contains("\"server\":{\"name\":\"greenmail\",\"host\":\"10.0.0.9\",\"port\":3143,\"tls\":\"None\"}"))
        assertTrue(first.contains("\"protocol\":\"imap\""))
    }

    @Test
    fun loginLiteralsAreRedacted() {
        val r = recorder()
        r.accept("main", TrafficLog.LOG_SENT, "A1 LOGIN {3+}\r\nada {13+}\r\nhunter2secret\r\n".toByteArray())
        r.accept("main", TrafficLog.LOG_SENT, "A2 SELECT {5+}\r\nINBOX\r\n".toByteArray())
        val all = lines(r).joinToString("\n")
        val b64 = Base64.getEncoder().encodeToString("hunter2secret".toByteArray())
        assertFalse(all, all.contains("hunter2secret") || all.contains(b64))
        assertTrue(all, all.contains(Base64.getEncoder().encodeToString("INBOX".toByteArray())))
    }

    @Test
    fun splitLiteralKeptInFull() {
        val r = recorder()
        r.accept("main", TrafficLog.LOG_RECEIVED, "* 1 FETCH (BODY[] {10}\r\nHel".toByteArray())
        r.accept("main", TrafficLog.LOG_RECEIVED, "lo Worl".toByteArray())
        r.accept("main", TrafficLog.LOG_RECEIVED, ")\r\n".toByteArray())
        val ls = lines(r)
        assertTrue(ls[1].contains("\"line\":\"* 1 FETCH (BODY[] {10}\""))
        val b64 = ls[2].substringAfter("\"literal_b64\":\"").substringBefore('"')
        assertEquals("Hello Worl", String(Base64.getDecoder().decode(b64)))
        assertTrue(ls[3].contains("\"line\":\")\""))
    }

    @Test
    fun credentialsRedacted() {
        val r = recorder()
        r.accept("main", TrafficLog.LOG_SENT, "a1 LOGIN user secret\r\n".toByteArray())
        r.accept("main", TrafficLog.LOG_SENT, "a2 AUTHENTICATE PLAIN\r\n".toByteArray())
        r.accept("main", TrafficLog.LOG_RECEIVED, "+ \r\n".toByteArray())
        r.accept("main", TrafficLog.LOG_SENT, "AHVzZXIAc2VjcmV0\r\n".toByteArray())
        r.accept("main", TrafficLog.LOG_SENT, "AUTH PLAIN AHVzZXIAc2VjcmV0\r\n".toByteArray())
        r.accept("main", TrafficLog.LOG_PRIVATE, "pw".toByteArray())
        val text = r.file.readText()
        assertFalse(text.contains("secret"))
        assertFalse(text.contains("AHVzZXIAc2VjcmV0"))
        assertTrue(text.contains("\"line\":\"a1 LOGIN\",\"redacted\":12"))
        assertTrue(text.contains("\"line\":\"a2 AUTHENTICATE PLAIN\""))
    }

    @Test
    fun repeatsNotCollapsedAndEventsKept() {
        val r = recorder()
        repeat(3) { r.accept("main", TrafficLog.LOG_SENT, "a NOOP\r\n".toByteArray()) }
        r.event("main", "connect", "10.0.0.9:3143")
        r.accept("watch", TrafficLog.LOG_RECEIVED, "* OK partial".toByteArray())
        r.flush()
        val ls = lines(r)
        assertEquals(3, ls.count { it.contains("\"line\":\"a NOOP\"") })
        assertTrue(ls.any { it.contains("\"dir\":\"E\",\"event\":\"connect\"") })
        assertTrue(ls.last().contains("\"conn\":\"watch\",\"dir\":\"S\",\"line\":\"* OK partial\""))
    }

    @Test
    fun privateAuthenticateKeepsMechanismOnly() {
        val r = recorder()
        r.accept("main", TrafficLog.LOG_PRIVATE, "4 AUTHENTICATE LOGIN\r\n".toByteArray())
        r.accept("main", TrafficLog.LOG_PRIVATE, "4 AUTHENTICATE PLAIN AGxpdmVpbWFwAHB3\r\n".toByteArray())
        r.accept("main", TrafficLog.LOG_PRIVATE, "bGl2ZWltYXA=\r\n".toByteArray())
        r.flush()
        val text = r.file.readText()
        assertTrue(text.contains("\"line\":\"4 AUTHENTICATE LOGIN\""))
        assertTrue(text.contains("\"line\":\"4 AUTHENTICATE PLAIN\""))
        assertFalse(text.contains("AGxpdmVpbWFwAHB3"))
        assertFalse(text.contains("bGl2ZWltYXA="))
    }
}
