package org.dlang.liveimap.engine.sieve

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class PlainSieveTransportTest {
    @Test(timeout = 20_000)
    fun localhostGreetingLogsOut() = runBlocking {
        val listen = ServerSocket()
        listen.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0))
        assertEquals("127.0.0.1", listen.inetAddress.hostAddress)
        val port = listen.localPort
        val received = AtomicReference<ByteArray>()
        val failure = AtomicReference<Throwable>()
        val thread = Thread {
            var client: Socket? = null
            try {
                listen.soTimeout = 10_000
                client = listen.accept()
                client.tcpNoDelay = true
                client.soTimeout = 10_000
                val output = client.getOutputStream()
                output.write(GREETING)
                output.flush()
                val got = readBytes(client.getInputStream(), LOGOUT.size)
                output.write(LOGOUT_OK)
                output.flush()
                val rest = drain(client.getInputStream())
                val all = ByteArray(got.size + rest.size)
                got.copyInto(all)
                rest.copyInto(all, got.size)
                received.set(all)
            } catch (error: Throwable) {
                failure.set(error)
            } finally {
                try {
                    client?.close()
                } catch (error: Exception) {
                }
            }
        }
        thread.isDaemon = true
        thread.start()
        try {
            val caps = greetPlain("127.0.0.1", port)
            thread.join(10_000)
            assertFalse(thread.isAlive)
            val error = failure.get()
            if (error != null) throw error
            assertEquals("Example timsieved", caps.implementation)
            assertEquals(listOf("fileinto"), caps.extensions)
            assertFalse(caps.startTls)
            assertEquals("LOGOUT\r\n", String(received.get(), StandardCharsets.UTF_8))
        } finally {
            try {
                listen.close()
            } catch (error: Exception) {
            }
            thread.join(2_000)
        }
    }
}

private val GREETING = (
    "\"IMPLEMENTATION\" \"Example timsieved\"\r\n" +
        "\"SIEVE\" \"fileinto\"\r\n" +
        "OK\r\n"
    ).toByteArray(StandardCharsets.UTF_8)

private val LOGOUT = "LOGOUT\r\n".toByteArray(StandardCharsets.UTF_8)

private val LOGOUT_OK = "OK \"done\"\r\n".toByteArray(StandardCharsets.UTF_8)

private fun readBytes(input: InputStream, count: Int): ByteArray {
    val got = ByteArray(count)
    var off = 0
    while (off < count) {
        val n = input.read(got, off, count - off)
        if (n < 0) break
        off += n
    }
    return got.copyOf(off)
}

private fun drain(input: InputStream): ByteArray {
    val rest = ByteArrayOutputStream()
    while (true) {
        val next = input.read()
        if (next < 0) break
        rest.write(next)
    }
    return rest.toByteArray()
}
