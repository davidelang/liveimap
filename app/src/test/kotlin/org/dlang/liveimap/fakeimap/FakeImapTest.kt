package org.dlang.liveimap.fakeimap

import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.Closeable
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Loopback checks against the pinned fakeimap binary (not the removed in-process FakeImapServer).
 * Exact recorded reply shapes are not asserted: those belonged to the old fake. Tests fail
 * immediately if the pin is still UNSELECTED or the binary has not been emerged.
 */
class FakeImapTest {
    @Test(timeout = 20_000)
    fun minimalProfile() {
        exercise(listOf("IMAP4rev1"), "A1 CAPABILITY")
    }

    @Test(timeout = 20_000)
    fun cyrus22Profile() {
        val caps = listOf(
            "IMAP4rev1", "NAMESPACE", "UIDPLUS", "LITERAL+", "CHILDREN",
            "UNSELECT", "SORT", "THREAD=REFERENCES", "IDLE",
        )
        exercise(caps, "A1 capability")
    }

    @Test(timeout = 20_000)
    fun closeEndsTheRead() {
        withServer { _, client, server ->
            server.armFault(mapOf("do" to "close", "command" to "CAPABILITY", "times" to 1))
            client.send("C1 CAPABILITY")
            assertTrue(client.readEnded())
        }
    }

    @Test(timeout = 20_000)
    fun silentStaysUnanswered() {
        withServer { _, client, server ->
            server.armFault(mapOf("do" to "silent", "command" to "CAPABILITY", "times" to 1))
            client.send("S1 CAPABILITY")
            assertTrue(client.stillUnanswered(1_000))
        }
    }

    @Test(timeout = 20_000)
    fun badAndNoAreTagged() {
        withServer { _, client, server ->
            server.armFault(mapOf("do" to "raw", "command" to "CAPABILITY", "times" to 1, "text" to "B1 BAD"))
            client.send("B1 CAPABILITY")
            assertEquals("B1 BAD", client.readLine())
            server.armFault(mapOf("do" to "raw", "command" to "CAPABILITY", "times" to 1, "text" to "B2 NO"))
            client.send("B2 CAPABILITY")
            assertEquals("B2 NO", client.readLine())
        }
    }

    @Test(timeout = 20_000)
    fun byeIsUntaggedAndCloses() {
        withServer { _, client, server ->
            // arm-fault do=bye (command + close after * BYE) is the mapping for the old
            // FakeImapStep.Bye; the control op "bye" only covers IDLE. See the gaps prompt.
            server.armFault(mapOf("do" to "bye", "command" to "CAPABILITY", "times" to 1))
            client.send("Y1 CAPABILITY")
            val line = client.readLine()
            assertTrue("expected * BYE, got $line", line != null && line.startsWith("* BYE"))
            assertTrue(client.readEnded())
        }
    }

    @Test(timeout = 20_000)
    fun newUidValidityOnNextSelect() {
        withServer { data, client, server ->
            login(client)
            val next = data.resolve("dataset-99")
            FakeimapPin.writeDataset(next, uidValidity = 99)
            server.control(mapOf("op" to "set-dataset", "path" to next.toAbsolutePath().toString()))
            // set-dataset closes existing IMAP connections (dropIMAP) before the store is replaced.
            LineClient(server.listenPort).use { c2 ->
                val greet = c2.readLine()
                assertTrue("expected * OK greeting after set-dataset, got $greet", greet != null && greet.startsWith("* OK"))
                login(c2)
                c2.send("U1 SELECT INBOX")
                val lines = readUntilTagged(c2, "U1")
                assertTrue(lines.joinToString("\n"), lines.any { it.contains("[UIDVALIDITY 99]") })
            }
        }
    }

    @Test(timeout = 20_000)
    fun expungeDuringFetchPrecedesFetch() {
        withServer { _, client, server ->
            login(client)
            client.send("S1 SELECT INBOX")
            readUntilTagged(client, "S1")
            // expunge_mid is widened in the gaps prompt: command may be FETCH (or any
            // IMAP command); the EXPUNGE is flushed before the tagged reply.
            server.armFault(mapOf("do" to "expunge_mid", "command" to "FETCH", "uid" to 1, "times" to 1))
            client.send("E1 FETCH 1 (FLAGS)")
            val lines = readUntilTagged(client, "E1")
            assertTrue("expected an EXPUNGE before the tagged reply, got $lines", lines.any { it.contains("EXPUNGE") })
            val exp = lines.indexOfFirst { it.contains("EXPUNGE") }
            val tag = lines.indexOfFirst { it.startsWith("E1 ") }
            assertTrue("EXPUNGE must precede the tagged reply: $lines", exp >= 0 && exp < tag)
        }
    }

    private fun withServer(block: (java.nio.file.Path, LineClient, FakeimapServer) -> Unit) {
        FakeimapPin.requireReady()
        val work = FakeimapPin.scratchDir("imap-test")
        val pers = FakeimapPin.writePersonality(
            work.resolve("personality.json"),
            preLogin = listOf("IMAP4rev1"),
            postLogin = listOf("IMAP4rev1"),
        )
        val data = FakeimapPin.writeDataset(work.resolve("dataset"))
        FakeimapServer.start("imap-test", pers, data).use { server ->
            assertEquals("127.0.0.1", server.listenHost)
            LineClient(server.listenPort).use { client ->
                val greet = client.readLine()
                assertTrue("expected * OK greeting, got $greet", greet != null && greet.startsWith("* OK"))
                block(work, client, server)
            }
        }
    }

    private fun exercise(caps: List<String>, capabilityCommand: String) {
        FakeimapPin.requireReady()
        val work = FakeimapPin.scratchDir("imap-exercise")
        val pers = FakeimapPin.writePersonality(
            work.resolve("personality.json"),
            preLogin = caps,
            postLogin = caps,
        )
        val data = FakeimapPin.writeDataset(work.resolve("dataset"))
        FakeimapServer.start("imap-exercise", pers, data).use { server ->
            assertEquals("127.0.0.1", server.listenHost)
            LineClient(server.listenPort).use { client ->
                val greet = client.readLine()
                assertTrue("expected * OK greeting, got $greet", greet != null && greet.startsWith("* OK"))
                client.send(capabilityCommand)
                val capLines = readUntilTagged(client, "A1")
                val cap = capLines.first { it.uppercase().contains("CAPABILITY") }
                for (token in caps) {
                    assertTrue("$token in $cap", cap.uppercase().split(' ').contains(token.uppercase()))
                }
                client.send("A2 LOGIN user \"\"")
                assertTrue(client.readLine()!!.startsWith("A2 NO"))
                client.send("A3 LOGIN user secret")
                assertTrue(client.readLine()!!.startsWith("A3 OK"))
                client.send("A4 LIST \"\" \"*\"")
                val list = readUntilTagged(client, "A4")
                assertTrue(list.joinToString("\n"), list.any { it.uppercase().contains("INBOX") })
                client.send("A5 SELECT INBOX")
                val sel = readUntilTagged(client, "A5")
                assertTrue(sel.joinToString("\n"), sel.any { it.contains("EXISTS") })
                assertTrue(sel.joinToString("\n"), sel.any { it.contains("UIDVALIDITY") })
                client.send("A6 FETCH 1 (FLAGS)")
                val fetch = readUntilTagged(client, "A6")
                assertTrue(fetch.joinToString("\n"), fetch.any { it.uppercase().contains("FETCH") && it.contains("FLAGS") })
                client.send("A7 LOGOUT")
                val bye = readUntilTagged(client, "A7")
                assertTrue(bye.joinToString("\n"), bye.any { it.startsWith("* BYE") })
            }
        }
    }

    private fun login(client: LineClient) {
        client.send("L1 LOGIN user secret")
        assertTrue(client.readLine()!!.startsWith("L1 OK"))
    }

    private fun readUntilTagged(client: LineClient, tag: String): List<String> {
        val out = ArrayList<String>()
        while (true) {
            val line = client.readLine() ?: error("connection closed before $tag; had $out")
            out.add(line)
            if (line.startsWith("$tag ")) return out
        }
    }
}

private class LineClient(port: Int) : Closeable {
    private val socket = Socket()
    private val reader: BufferedReader
    private val writer: BufferedWriter

    init {
        socket.connect(InetSocketAddress("127.0.0.1", port), 2_000)
        socket.tcpNoDelay = true
        socket.soTimeout = 2_000
        reader = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))
        writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8))
    }

    fun send(line: String) {
        writer.write(line)
        writer.write("\r\n")
        writer.flush()
    }

    fun readLine(): String? = readLine(2_000)

    fun readLine(timeoutMs: Int): String? {
        socket.soTimeout = timeoutMs
        return try {
            reader.readLine()
        } catch (e: SocketTimeoutException) {
            null
        } catch (e: IOException) {
            null
        }
    }

    fun stillUnanswered(timeoutMs: Int): Boolean {
        socket.soTimeout = timeoutMs
        return try {
            reader.readLine()
            false
        } catch (e: SocketTimeoutException) {
            true
        } catch (e: IOException) {
            false
        }
    }

    fun readEnded(timeoutMs: Int = 2_000): Boolean {
        socket.soTimeout = timeoutMs
        return try {
            reader.readLine() == null
        } catch (e: SocketTimeoutException) {
            false
        } catch (e: IOException) {
            true
        }
    }

    override fun close() {
        socket.close()
    }
}
