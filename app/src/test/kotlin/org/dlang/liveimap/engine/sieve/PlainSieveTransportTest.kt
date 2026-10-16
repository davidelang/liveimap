package org.dlang.liveimap.engine.sieve

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
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

    @Test(timeout = 10_000)
    fun closeWritesNothing() = runBlocking {
        val listen = ServerSocket()
        listen.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0))
        val port = listen.localPort
        val received = AtomicReference<ByteArray>()
        val thread = Thread {
            val client = listen.accept()
            try {
                received.set(drain(client.getInputStream()))
            } finally {
                try {
                    client.close()
                } catch (error: Exception) {
                }
            }
        }
        thread.isDaemon = true
        thread.start()
        try {
            val transport = openPlainSieve("127.0.0.1", port)
            transport.close()
            transport.close()
            thread.join(5_000)
            assertFalse(thread.isAlive)
            assertEquals(0, received.get().size)
        } finally {
            try {
                listen.close()
            } catch (error: Exception) {
            }
            thread.join(2_000)
        }
    }

    @Test(timeout = 10_000)
    fun closeFailsABlockedRead() = runBlocking {
        val listen = ServerSocket()
        listen.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0))
        val port = listen.localPort
        val accepted = CountDownLatch(1)
        val server = Thread {
            val client = listen.accept()
            accepted.countDown()
            try {
                Thread.sleep(30_000)
            } catch (error: InterruptedException) {
            } finally {
                try {
                    client.close()
                } catch (error: Exception) {
                }
            }
        }
        server.isDaemon = true
        server.start()
        val error = AtomicReference<Throwable>()
        var reader: Thread? = null
        try {
            val transport = openPlainSieve("127.0.0.1", port)
            assertTrue(accepted.await(5, TimeUnit.SECONDS))
            reader = Thread {
                try {
                    runBlocking { transport.readLine() }
                } catch (thrown: Throwable) {
                    error.set(thrown)
                }
            }
            reader.isDaemon = true
            reader.start()
            Thread.sleep(200)
            val start = System.nanoTime()
            transport.close()
            reader.join(5_000)
            val ms = (System.nanoTime() - start) / 1_000_000
            assertFalse(reader.isAlive)
            assertTrue("read took $ms ms", ms < 5_000)
            assertTrue(error.get() is SieveFailure)
        } finally {
            server.interrupt()
            try {
                listen.close()
            } catch (closeError: Exception) {
            }
            server.join(2_000)
            reader?.join(2_000)
        }
    }

    @Test(timeout = 20_000)
    fun getScriptKeepsTheLiteralAndDropsTheTail() {
        withLocalSieve("{5}\r\nkeep;\r\nOK \"got\"\r\n") { transport ->
            assertEquals("keep;", getScript(transport, "liveimap"))
        }
    }

    @Test(timeout = 20_000)
    fun failedLiteralLeavesTheNextResponse() {
        withLocalSieve("NO {4}\r\nline\r\nOK \"next\"\r\n") { transport ->
            try {
                getScript(transport, "liveimap")
                fail("expected SieveFailure")
            } catch (failure: SieveFailure) {
                assertEquals("line", failure.text)
            }
            assertEquals("OK \"next\"", transport.readLine())
        }
    }

    @Test(timeout = 20_000)
    fun emptyLiteralReturnsAnEmptyScript() {
        withLocalSieve("{0}\r\n\r\nOK\r\n") { transport ->
            assertEquals("", getScript(transport, "liveimap"))
        }
    }

    @Test
    fun sieveTlsProtocolsDropsLegacyAndKeepsOrder() {
        assertEquals(
            listOf("TLSv1.2", "TLSv1.3"),
            sieveTlsProtocols(arrayOf("TLSv1", "TLSv1.2", "TLSv1.3")),
        )
        assertEquals(
            listOf("TLSv1.3", "TLSv1.2"),
            sieveTlsProtocols(arrayOf("TLSv1.3", "TLSv1", "TLSv1.2")),
        )
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

private fun withLocalSieve(response: String, block: suspend (PlainSieveTransport) -> Unit) {
    val listen = ServerSocket()
    listen.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0))
    val failure = AtomicReference<Throwable>()
    val thread = Thread {
        var client: Socket? = null
        try {
            listen.soTimeout = 10_000
            client = listen.accept()
            client.tcpNoDelay = true
            client.soTimeout = 10_000
            val output = client.getOutputStream()
            output.write(response.toByteArray(StandardCharsets.UTF_8))
            output.flush()
            drain(client.getInputStream())
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
    var transport: PlainSieveTransport? = null
    var thrown: Throwable? = null
    try {
        transport = openPlainSieve("127.0.0.1", listen.localPort)
        runBlocking { block(transport) }
    } catch (error: Throwable) {
        thrown = error
    } finally {
        try {
            transport?.close()
        } catch (error: Exception) {
        }
        try {
            listen.close()
        } catch (error: Exception) {
        }
        thread.join(10_000)
    }
    if (thrown != null) throw thrown
    assertFalse(thread.isAlive)
    val error = failure.get()
    if (error != null) throw error
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
