package org.dlang.liveimap.fakeimap

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import org.dlang.liveimap.engine.ServerProbe
import org.dlang.liveimap.engine.checkServer
import org.dlang.liveimap.settings.Settings
import org.dlang.liveimap.settings.TlsMode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F40 cases against the pinned fakeimap binary: STARTTLS omitted (no cert, as a
 * stripped-relay stand-in) versus a cert-bearing server that should refuse a
 * cleartext LOGIN when LOGINDISABLED is listed. The IMAP client itself is native;
 * these drive loopback exchanges and check ServerCheck. Fail loudly if the pin
 * is still UNSELECTED or the binary has not been emerged.
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

    @Test(timeout = 30_000)
    fun strippedStartTlsIsAConflictAndNoPasswordIsSent() {
        // The old FakeImapStep.StripStartTls flipped mid-session. fakeimap has no
        // mid-connection MITM op; a server started without a certificate already
        // omits STARTTLS. That is the mapping, not a silent fallback to the old fake.
        FakeimapPin.requireReady()
        val work = FakeimapPin.scratchDir("f40-strip")
        val pers = FakeimapPin.writePersonality(
            work.resolve("personality.json"),
            preLogin = listOf("IMAP4rev1", "STARTTLS", "LOGINDISABLED", "LITERAL+", "IDLE"),
            postLogin = listOf("IMAP4rev1", "LITERAL+", "IDLE"),
        )
        val data = FakeimapPin.writeDataset(work.resolve("dataset"))
        FakeimapServer.start("f40-strip", pers, data).use { server ->
            Wire(server.listenPort).use { w ->
                w.until("* OK")
                w.send("A1 CAPABILITY\r\n")
                val caps = w.until("A1 ")
                assertFalse(caps.contains("STARTTLS"))
                w.send("A2 STARTTLS\r\n")
                val starttls = w.until("A2 ")
                assertTrue(starttls, starttls.lineSequence().any { it.startsWith("A2 BAD") })
                val pre = caps.lines().first { it.uppercase().contains("CAPABILITY") }
                    .substringAfter("CAPABILITY ").trim()
                val probe = ServerProbe.parse(arrayOf("pre=$pre", "starttls=absent"))
                val verdict = checkServer(Settings(tlsMode = TlsMode.StartTls), probe)
                assertTrue(verdict.conflicts.toString(), verdict.conflicts.any { it.contains("does not offer STARTTLS") })
            }
            val sent = server.commandLog().joinToString("\n")
            assertFalse(sent.contains("LOGIN", ignoreCase = true))
        }
    }

    @Test(timeout = 30_000)
    fun unstrippedServerStillRefusesClearLogin() {
        FakeimapPin.requireReady()
        val work = FakeimapPin.scratchDir("f40-tls")
        val (cert, key) = FakeimapPin.pemPair(work.resolve("certs"))
        val pers = FakeimapPin.writePersonality(
            work.resolve("personality.json"),
            preLogin = listOf("IMAP4rev1", "STARTTLS", "LOGINDISABLED", "LITERAL+", "IDLE"),
            postLogin = listOf("IMAP4rev1", "LITERAL+", "IDLE"),
        )
        val data = FakeimapPin.writeDataset(work.resolve("dataset"))
        FakeimapServer.start("f40-tls", pers, data, cert = cert, key = key).use { server ->
            Wire(server.listenPort).use { w ->
                w.until("* OK")
                w.send("A1 CAPABILITY\r\n")
                assertTrue(w.until("A1 ").contains("STARTTLS"))
                w.send("A2 LOGIN ada pw\r\n")
                assertTrue(w.until("A2 ").startsWith("A2 NO"))
            }
        }
    }
}
