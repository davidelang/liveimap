package org.dlang.liveimap.engine.sieve

import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import org.dlang.liveimap.engine.TrafficLog
import org.dlang.liveimap.fakeimap.FakeimapPin
import org.dlang.liveimap.fakeimap.FakeimapServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class PlainSieveTransportTest {
    @Test(timeout = 30_000)
    fun pinGreetingLogsOut() = runBlocking {
        withPin { server ->
            val caps = greetPlain(server.listenHost, sievePort(server), null)
            assertEquals("fakeimap", caps.implementation)
            assertTrue(caps.extensions.contains("fileinto"))
            assertFalse(caps.startTls)
        }
    }

    @Test(timeout = 30_000)
    fun closeLeavesTheListenerUp() = runBlocking {
        withPin { server ->
            val host = server.listenHost
            val port = sievePort(server)
            val transport = openPlainSieve(host, port, null)
            transport.close()
            transport.close()
            val caps = greetPlain(host, port, null)
            assertEquals("fakeimap", caps.implementation)
        }
    }

    @Test(timeout = 30_000)
    fun closeFailsABlockedRead() = runBlocking {
        withPin { server ->
            val transport = openPlainSieve(server.listenHost, sievePort(server), null)
            readGreeting(transport)
            val error = AtomicReference<Throwable>()
            val reader = Thread {
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
        }
    }

    @Test(timeout = 30_000)
    fun cramAuthenticateIsPrivate() = runBlocking {
        val root = java.nio.file.Files.createTempDirectory("sieve-log").toFile()
        val writer = TrafficLog.writer(root, "acct-1", 0)
        withPin { server ->
            val transport = openPlainSieve(server.listenHost, sievePort(server), writer)
            try {
                readGreeting(transport)
                authenticateCramMd5(transport, "demo", "secret")
                listScripts(transport)
                writer.sync()
            } finally {
                transport.close()
            }
        }
        val records = TrafficLog.recordsIn(java.io.File(java.io.File(root, "traffic"), "acct-1"))
        val sent = records.filter { it.dir == '>' }.joinToString("\n") { String(it.bytes, StandardCharsets.UTF_8) }
        assertTrue(records.any { it.dir == '>' && String(it.bytes, StandardCharsets.US_ASCII).startsWith("<private ") })
        assertTrue(sent.contains("LISTSCRIPTS"))
        assertFalse(sent.contains("secret"))
    }

    @Test(timeout = 30_000)
    fun putScriptNoLeavesTheScriptUnstored() = runBlocking {
        withPin { server ->
            val transport = authed(server)
            try {
                server.armFault(mapOf(
                    "do" to "reply",
                    "proto" to "sieve",
                    "command" to "PUTSCRIPT",
                    "times" to 1,
                ))
                try {
                    putScript(transport, "faulted", "keep;\n")
                    fail("expected SieveFailure")
                } catch (failure: SieveFailure) {
                    assertEquals("fault", failure.text)
                }
            } finally {
                transport.close()
            }
            val listed = authed(server)
            try {
                assertFalse(listScripts(listed).any { it.name == "faulted" })
            } finally {
                listed.close()
            }
        }
    }

    @Test(timeout = 30_000)
    fun putScriptCloseFails() = runBlocking {
        withPin { server ->
            val transport = authed(server)
            try {
                var failed = false
                try {
                    putScript(transport, "closed", "keep;\n" + " ".repeat(1 shl 20))
                } catch (_: Throwable) {
                    failed = true
                }
                assertTrue(failed)
            } finally {
                transport.close()
            }
            val listed = authed(server)
            try {
                assertFalse(listScripts(listed).any { it.name == "closed" })
            } finally {
                listed.close()
            }
        }
    }

    @Test(timeout = 30_000)
    fun getScriptRoundTrip() = runBlocking {
        withPin { server ->
            val transport = authed(server)
            try {
                putScript(transport, "liveimap", "keep;\n")
                assertEquals("keep;\n", getScript(transport, "liveimap"))
            } finally {
                transport.close()
            }
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

private fun sievePort(server: FakeimapServer): Int {
    val bound = server.bound["sieve"] as? String ?: error("no sieve listener: ${server.bound}")
    return bound.substringAfterLast(":").toInt()
}

private fun withPin(block: suspend (FakeimapServer) -> Unit) = runBlocking {
    FakeimapPin.requireReady()
    val server = FakeimapServer.start(
        "sieve-transport",
        FakeimapPin.personalityPath("binary"),
        FakeimapPin.personalities().parent.resolve("datasets/demo"),
        extra = mapOf("sieve_listen" to "127.0.0.1:0"),
    )
    try {
        block(server)
    } finally {
        server.close()
    }
}

private suspend fun authed(server: FakeimapServer): PlainSieveTransport {
    val transport = openPlainSieve(server.listenHost, sievePort(server), null)
    readGreeting(transport)
    authenticatePlain(transport, "demo", "secret")
    return transport
}
