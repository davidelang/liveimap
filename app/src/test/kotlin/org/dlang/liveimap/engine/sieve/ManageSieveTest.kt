package org.dlang.liveimap.engine.sieve

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ManageSieveTest {
    @Test
    fun readsTheGreeting() = runBlocking {
        val transport = ListTransport(
            listOf(
                "\"IMPLEMENTATION\" \"Example timsieved\"",
                "\"SASL\" \"CRAM-MD5\" \"PLAIN\"",
                "\"SIEVE\" \"fileinto\" \"imap4flags\" \"include\" \"redirect\"",
                "\"STARTTLS\"",
                "\"VERSION\" \"1.0\"",
                "OK",
            ),
        )
        val caps = readGreeting(transport)
        assertEquals("Example timsieved", caps.implementation)
        assertEquals(listOf("CRAM-MD5", "PLAIN"), caps.sasl)
        assertEquals(
            listOf("fileinto", "imap4flags", "include", "redirect"),
            caps.extensions,
        )
        assertTrue(caps.startTls)
        assertEquals("1.0", caps.version)
    }

    @Test
    fun splitsOneExtensionString() = runBlocking {
        val transport = ListTransport(
            listOf(
                "\"SIEVE\" \"fileinto imap4flags\"",
                "OK",
            ),
        )
        val caps = readGreeting(transport)
        assertEquals(listOf("fileinto", "imap4flags"), caps.extensions)
        assertFalse(caps.startTls)
        assertEquals("", caps.version)
    }

    @Test
    fun noTextIsTheFailure() = runBlocking {
        val transport = ListTransport(listOf("NO \"no sieve\""))
        try {
            readGreeting(transport)
            fail("expected SieveFailure")
        } catch (failure: SieveFailure) {
            assertEquals("no sieve", failure.text)
        }
    }

    @Test
    fun logoutWritesLogout() = runBlocking {
        val transport = ListTransport(listOf("OK \"done\""))
        logout(transport)
        assertEquals(listOf("LOGOUT"), transport.written)
        assertEquals(listOf("OK \"done\""), transport.read)
    }
}

private class ListTransport(lines: List<String>) : SieveLineTransport {
    private val pending = lines.toMutableList()
    val written = ArrayList<String>()
    val read = ArrayList<String>()

    override suspend fun readLine(): String {
        val line = pending.removeAt(0)
        read.add(line)
        return line
    }

    override suspend fun writeLine(line: String) {
        written.add(line)
    }
}
