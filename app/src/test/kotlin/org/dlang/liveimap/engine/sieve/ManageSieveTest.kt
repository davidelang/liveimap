package org.dlang.liveimap.engine.sieve

import java.nio.charset.StandardCharsets
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
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

    @Test
    fun listsScripts() = runBlocking {
        val transport = ListTransport(
            listOf("\"liveimap\"", "\"vacation\" ACTIVE", "OK"),
        )
        val scripts = listScripts(transport)
        assertEquals(listOf("LISTSCRIPTS"), transport.written)
        assertEquals(
            listOf(
                ListedScript("liveimap", active = false),
                ListedScript("vacation", active = true),
            ),
            scripts,
        )
    }

    @Test
    fun getsALiteral() = runBlocking {
        val transport = ListTransport(
            listOf("{5}", "OK"),
            listOf("keep;".toByteArray(StandardCharsets.UTF_8)),
        )
        val text = getScript(transport, "liveimap")
        assertEquals(listOf("GETSCRIPT \"liveimap\""), transport.written)
        assertEquals(listOf("{5}", "OK"), transport.read)
        assertEquals("keep;", text)
    }

    @Test
    fun putReturnsWarnings() = runBlocking {
        val script = "keep;\n"
        val transport = ListTransport(
            listOf("OK (WARNINGS) \"missing semicolon\""),
        )
        val warnings = putScript(transport, "liveimap", script)
        assertEquals(listOf("PUTSCRIPT \"liveimap\" {6+}"), transport.written)
        assertArrayEquals(script.toByteArray(StandardCharsets.UTF_8), transport.writtenBytes.single())
        assertEquals(6, transport.writtenBytes.single().size)
        assertEquals("missing semicolon", warnings)
    }

    @Test
    fun checkRejectsWithServerText() = runBlocking {
        val transport = ListTransport(listOf("NO \"line 1\""))
        try {
            checkScript(transport, "bad;")
            fail("expected SieveFailure")
        } catch (failure: SieveFailure) {
            assertEquals("line 1", failure.text)
        }
        assertEquals(listOf("CHECKSCRIPT {4+}"), transport.written)
        assertArrayEquals("bad;".toByteArray(StandardCharsets.UTF_8), transport.writtenBytes.single())
    }
}

private class ListTransport(
    lines: List<String>,
    chunks: List<ByteArray> = emptyList(),
) : SieveLineTransport {
    private val pending = lines.toMutableList()
    private val pendingBytes = chunks.toMutableList()
    val written = ArrayList<String>()
    val writtenBytes = ArrayList<ByteArray>()
    val read = ArrayList<String>()

    override suspend fun readLine(): String {
        val line = pending.removeAt(0)
        read.add(line)
        return line
    }

    override suspend fun writeLine(line: String) {
        written.add(line)
    }

    override suspend fun readBytes(count: Int): ByteArray {
        if (count < 0) throw SieveFailure("literal")
        val chunk = pendingBytes.removeAt(0)
        if (chunk.size != count) throw SieveFailure("read")
        return chunk
    }

    override suspend fun writeBytes(bytes: ByteArray) {
        writtenBytes.add(bytes.copyOf())
    }
}
