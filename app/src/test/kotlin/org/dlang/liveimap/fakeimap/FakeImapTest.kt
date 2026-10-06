package org.dlang.liveimap.fakeimap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
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

class FakeImapTest {
    @Test(timeout = 20_000)
    fun minimalProfile() {
        exercise(FakeImapProfile.Minimal, "A1 CAPABILITY")
    }

    @Test(timeout = 20_000)
    fun cyrus22Profile() {
        exercise(FakeImapProfile.Cyrus22, "A1 capability")
    }

    @Test(timeout = 20_000)
    fun closeEndsTheRead() {
        withServer { server, client ->
            server.script(FakeImapStep.Close)
            client.send("C1 CAPABILITY")
            assertTrue(client.readEnded())
        }
    }

    @Test(timeout = 20_000)
    fun silentStaysUnanswered() {
        withServer { server, client ->
            server.script(FakeImapStep.Silent)
            client.send("S1 CAPABILITY")
            assertTrue(client.stillUnanswered(1_000))
        }
    }

    @Test(timeout = 20_000)
    fun badAndNoAreTagged() {
        withServer { server, client ->
            server.script(FakeImapStep.Bad)
            client.send("B1 CAPABILITY")
            assertEquals("B1 BAD", client.readLine())
            server.script(FakeImapStep.No)
            client.send("B2 CAPABILITY")
            assertEquals("B2 NO", client.readLine())
        }
    }

    @Test(timeout = 20_000)
    fun byeIsUntaggedAndCloses() {
        withServer { server, client ->
            server.script(FakeImapStep.Bye)
            client.send("Y1 CAPABILITY")
            assertEquals("* BYE", client.readLine())
            assertTrue(client.readEnded())
        }
    }

    @Test(timeout = 20_000)
    fun newUidValidityOnNextSelect() {
        withServer { server, client ->
            server.script(FakeImapStep.NewUidValidity)
            client.send("U1 SELECT INBOX")
            assertEquals("* 1 EXISTS", client.readLine())
            assertEquals("* OK [UIDVALIDITY 99] UIDs valid", client.readLine())
            assertEquals("U1 OK [READ-WRITE]", client.readLine())
        }
    }

    @Test(timeout = 20_000)
    fun expungeDuringFetchPrecedesFetch() {
        withServer { server, client ->
            server.script(FakeImapStep.ExpungeDuringFetch)
            client.send("E1 FETCH 1 (FLAGS)")
            assertEquals("* 1 EXPUNGE", client.readLine())
            assertEquals("* 1 FETCH (FLAGS (\\Seen))", client.readLine())
            assertEquals("E1 OK", client.readLine())
        }
    }

    private fun withServer(block: (FakeImapServer, LineClient) -> Unit) {
        FakeImapServer(FakeImapProfile.Minimal).use { server ->
            assertEquals("127.0.0.1", server.boundHost)
            LineClient(server.port).use { client ->
                assertEquals("* OK fake ready", client.readLine())
                block(server, client)
            }
        }
    }

    private fun exercise(profile: FakeImapProfile, capabilityCommand: String) {
        FakeImapServer(profile).use { server ->
            assertEquals("127.0.0.1", server.boundHost)
            LineClient(server.port).use { client ->
                assertEquals("* OK fake ready", client.readLine())
                client.send(capabilityCommand)
                assertEquals("* CAPABILITY ${profile.capability}", client.readLine())
                assertEquals("A1 OK", client.readLine())
                client.send("A2 LOGIN user \"\"")
                assertEquals("A2 NO", client.readLine())
                client.send("A3 LOGIN user secret")
                assertEquals("A3 OK", client.readLine())
                client.send("A4 LIST \"\" \"\"")
                assertEquals("* LIST (\\Noinferiors) NIL INBOX", client.readLine())
                assertEquals("A4 OK", client.readLine())
                client.send("A5 SELECT INBOX")
                assertEquals("* 1 EXISTS", client.readLine())
                assertEquals("* OK [UIDVALIDITY 17] UIDs valid", client.readLine())
                assertEquals("A5 OK [READ-WRITE]", client.readLine())
                client.send("A6 FETCH 1 (FLAGS)")
                assertEquals("* 1 FETCH (FLAGS (\\Seen))", client.readLine())
                assertEquals("A6 OK", client.readLine())
                client.send("A7 LOGOUT")
                assertEquals("* BYE", client.readLine())
                assertEquals("A7 OK", client.readLine())
            }
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
