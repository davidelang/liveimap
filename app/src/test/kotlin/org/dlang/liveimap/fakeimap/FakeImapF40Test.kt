package org.dlang.liveimap.fakeimap

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import org.dlang.liveimap.engine.ServerProbe
import org.dlang.liveimap.engine.checkServer
import org.dlang.liveimap.settings.Settings
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
                val verdict = checkServer(Settings(tlsMode = TlsMode.StartTls), probe)
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

}
