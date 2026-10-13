package org.dlang.liveimap.fakeimap

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.file.Files
import org.dlang.liveimap.engine.ServerProbe
import org.dlang.liveimap.engine.TrafficLog
import org.dlang.liveimap.engine.TrafficRecorder
import org.dlang.liveimap.engine.checkServer
import org.dlang.liveimap.settings.AccountSettings
import org.dlang.liveimap.settings.TlsMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F40: FakeImapServer cases for STARTTLS stripping, LITERAL+ redaction, a VANISHED flood and IDLE bursts.
 * The IMAP client itself is native (not reachable from the JVM); these drive real loopback exchanges and check the
 * JVM code that judges or records them: ServerCheck, TrafficLog and TrafficRecorder.
 */
class FakeImapF40Test {
    /** A raw client that keeps every byte it sent and received, as the native logger hands them over. */
    private class Wire(port: Int) : AutoCloseable {
        val socket = Socket().apply { connect(InetSocketAddress("127.0.0.1", port), 5000); soTimeout = 10_000 }
        private val input: InputStream = socket.getInputStream()
        val sent = ArrayList<ByteArray>()
        val got = ArrayList<ByteArray>()

        fun send(text: String) {
            val b = text.toByteArray(Charsets.US_ASCII)
            sent.add(b)
            socket.getOutputStream().write(b)
            socket.getOutputStream().flush()
        }

        /** Reads until a line starting with [prefix] arrived; returns all text read for it. */
        fun until(prefix: String): String {
            val all = ByteArrayOutputStream()
            val line = ByteArrayOutputStream()
            while (true) {
                val c = input.read()
                if (c < 0) break
                all.write(c)
                line.write(c)
                if (c == '\n'.code) {
                    if (line.toString(Charsets.US_ASCII.name()).startsWith(prefix)) break
                    line.reset()
                }
            }
            got.add(all.toByteArray())
            return all.toString(Charsets.US_ASCII.name())
        }

        override fun close() = socket.close()
    }

    private fun tempFile(name: String): File = File(Files.createTempDirectory("f40").toFile(), name)

    private fun recorder() = TrafficRecorder(
        tempFile("rec.jsonl"),
        TrafficRecorder.RunHeader("liveimap", "test", "junit", "fake", "127.0.0.1", 0, "none", "imap", "f40"),
        clock = { 1L },
    )

    // ---- STARTTLS stripping ----

    @Test(timeout = 30_000)
    fun strippedStartTlsIsAConflictAndNoPasswordIsSent() {
        FakeImapServer(FakeImapProfile.StartTls).use { server ->
            Wire(server.port).use { w ->
                w.until("* OK")
                server.script(FakeImapStep.StripStartTls)
                w.send("A1 CAPABILITY\r\n")
                val caps = w.until("A1 ")
                assertFalse(caps.contains("STARTTLS"))
                w.send("A2 STARTTLS\r\n")
                assertTrue(w.until("A2 ").startsWith("A2 BAD"))
                val pre = caps.lines().first { it.startsWith("* CAPABILITY ") }.removePrefix("* CAPABILITY ").trim()
                // What the native probe reports for this server, judged by the app's check.
                val probe = ServerProbe.parse(arrayOf("pre=$pre", "starttls=absent"))
                val verdict = checkServer(AccountSettings(tlsMode = TlsMode.StartTls), probe)
                assertTrue(verdict.conflicts.toString(), verdict.conflicts.any { it.contains("does not offer STARTTLS") })
            }
            // The exchange never carried a LOGIN.
            assertFalse(server.received.any { it.contains("LOGIN", ignoreCase = true) })
        }
    }

    @Test(timeout = 30_000)
    fun unstrippedServerStillRefusesClearLogin() {
        FakeImapServer(FakeImapProfile.StartTls).use { server ->
            Wire(server.port).use { w ->
                w.until("* OK")
                w.send("A1 CAPABILITY\r\n")
                assertTrue(w.until("A1 ").contains("STARTTLS"))
                w.send("A2 LOGIN ada pw\r\n")
                assertTrue(w.until("A2 ").startsWith("A2 NO"))
            }
        }
    }

    // ---- LITERAL+ redaction ----

    @Test(timeout = 30_000)
    fun literalPlusLoginIsRedactedInBothLogs() {
        val secret = "hunter2secret"
        FakeImapServer(FakeImapProfile.Cyrus22).use { server ->
            Wire(server.port).use { w ->
                w.until("* OK")
                // LITERAL+: the literals are sent without waiting, split across writes mid-literal.
                w.send("A1 LOGIN {3+}\r\nada {${secret.length}+}\r\nhunt")
                w.send("er2secret\r\n")
                assertEquals("A1 OK\r\n", w.until("A1 "))
                assertTrue(server.received.contains("A1 LOGIN \"ada\" \"$secret\""))
                for (split in listOf(1, 3, 7, 1000)) {
                    val log = TrafficLog(tempFile("t.log"), clock = { 1L })
                    val rec = recorder()
                    val bytes = w.sent.fold(ByteArray(0)) { a, b -> a + b }
                    bytes.toList().chunked(split).forEach { chunk ->
                        log.accept("main", TrafficLog.LOG_SENT, chunk.toByteArray())
                        rec.accept("main", TrafficLog.LOG_SENT, chunk.toByteArray())
                    }
                    rec.flush()
                    assertFalse("log split $split", log.file.readText().contains("hunter"))
                    assertFalse("recorder split $split", rec.file.readText().contains("hunter"))
                    assertFalse("recorder b64 split $split", rec.file.readText().contains(java.util.Base64.getEncoder().encodeToString(secret.toByteArray())))
                }
            }
        }
    }

    // ---- VANISHED flood ----

    @Test(timeout = 60_000)
    fun vanishedFloodStaysWithinTheLogCap() {
        FakeImapServer(FakeImapProfile.Cyrus22).use { server ->
            server.vanishedFlood = 150_000
            Wire(server.port).use { w ->
                w.until("* OK")
                server.script(FakeImapStep.VanishedFlood)
                w.send("V1 SELECT INBOX\r\n")
                val text = w.until("V1 ")
                assertEquals(150_000, text.lines().count { it.startsWith("* VANISHED (EARLIER) ") })
                assertTrue(text.contains("V1 OK"))
                val log = TrafficLog(tempFile("t.log"), clock = { 1L })
                for (b in w.got) log.accept("main", TrafficLog.LOG_RECEIVED, b)
                assertTrue(log.file.length() <= TrafficLog.MAX_BYTES)
                assertTrue(log.file.readText().contains("V1 OK"))
                // The recorder keeps every line (no collapse): the replay tool needs the flood as it came.
                val rec = recorder()
                for (b in w.got) rec.accept("main", TrafficLog.LOG_RECEIVED, b)
                rec.flush()
                assertEquals(150_000, rec.file.readLines().count { it.contains("VANISHED (EARLIER)") })
            }
        }
    }

    // ---- IDLE bursts ----

    @Test(timeout = 30_000)
    fun idleBurstInOnePacketIsSeparateLines() {
        val burst = listOf("* 5 EXISTS", "* 3 EXPUNGE", "* 2 FETCH (FLAGS (\\Seen))", "* 4 EXISTS", "* STATUS INBOX (MESSAGES 4)")
        FakeImapServer(FakeImapProfile.Cyrus22).use { server ->
            server.idleBurst = burst
            Wire(server.port).use { w ->
                w.until("* OK")
                w.send("I1 IDLE\r\n")
                val text = w.until("* STATUS")
                assertTrue(text.startsWith("+ idling\r\n"))
                for (l in burst) assertTrue(l, text.contains(l + "\r\n"))
                w.send("DONE\r\n")
                assertTrue(w.until("I1 ").startsWith("I1 OK"))
                val rec = recorder()
                for (b in w.got) rec.accept("main", TrafficLog.LOG_RECEIVED, b)
                rec.flush()
                val lines = rec.file.readLines()
                for (l in burst) {
                    assertEquals(l, 1, lines.count { it.contains("\"line\":" + org.dlang.liveimap.engine.jq(l)) })
                }
                // and in order
                val idx = burst.map { l -> lines.indexOfFirst { it.contains(org.dlang.liveimap.engine.jq(l)) } }
                assertEquals(idx.sorted(), idx)
            }
            assertTrue(server.received.contains("DONE"))
        }
    }
}
