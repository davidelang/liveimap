package org.dlang.liveimap.fakeimap

import org.junit.Assert.assertEquals
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

    override fun close() {
        socket.close()
    }
}
