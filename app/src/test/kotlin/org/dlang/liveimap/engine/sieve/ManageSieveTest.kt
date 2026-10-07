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

    @Test
    fun includeLineIsAppendedOnce() {
        assertEquals("liveimap", liveimapScriptName)
        assertEquals("include :personal \"liveimap\";", liveimapIncludeLine)
        assertEquals("$liveimapIncludeLine\n", withLiveimapInclude(""))
        assertEquals("keep;\n$liveimapIncludeLine\n", withLiveimapInclude("keep;"))
        val ended = "keep; \r\n"
        assertEquals(ended + liveimapIncludeLine + "\n", withLiveimapInclude(ended))
        val present = "keep;\n  $liveimapIncludeLine  \nredirect \"a\";"
        assertEquals(present, withLiveimapInclude(present))
    }

    @Test
    fun plansEachActivationBranch() {
        val liveimap = ListedScript("liveimap", active = true)
        val vacation = ListedScript("vacation", active = true)
        val idle = ListedScript("vacation", active = false)
        val already = planLiveimapActivation(
            listOf("fileinto", "INCLUDE"),
            listOf(liveimap, vacation),
            "",
        )
        assertEquals(LiveimapActivateAction.None, already.action)
        assertEquals("liveimap", already.activeName)
        assertEquals("", already.rewritten)

        val emptyText = planLiveimapActivation(
            listOf("INCLUDE"),
            listOf(idle, vacation),
            "",
        )
        assertEquals(LiveimapActivateAction.None, emptyText.action)
        assertEquals("vacation", emptyText.activeName)
        assertEquals("", emptyText.rewritten)

        val matched = "keep;\n\t$liveimapIncludeLine\t"
        val present = planLiveimapActivation(listOf("include"), listOf(vacation), matched)
        assertEquals(LiveimapActivateAction.None, present.action)
        assertEquals("vacation", present.activeName)
        assertEquals("", present.rewritten)

        val missing = planLiveimapActivation(listOf("fileinto", "Include"), listOf(vacation), "keep;")
        assertEquals(LiveimapActivateAction.Include, missing.action)
        assertEquals("vacation", missing.activeName)
        assertEquals("keep;\n$liveimapIncludeLine\n", missing.rewritten)

        val noInclude = planLiveimapActivation(
            listOf("fileinto include", "included"),
            listOf(vacation),
            "keep;",
        )
        assertEquals(LiveimapActivateAction.SetActive, noInclude.action)
        assertEquals("vacation", noInclude.activeName)
        assertEquals("", noInclude.rewritten)

        val none = planLiveimapActivation(listOf("include"), listOf(idle), "keep;")
        assertEquals(LiveimapActivateAction.SetActive, none.action)
        assertEquals("", none.activeName)
        assertEquals("", none.rewritten)
    }

    @Test
    fun uploadNamesLiveimapOnly() = runBlocking {
        val script = "keep;\n"
        val transport = ListTransport(listOf("OK", "OK (WARNINGS) \"note\""))
        assertEquals("note", uploadLiveimap(transport, script))
        assertEquals(
            listOf("CHECKSCRIPT {6+}", "PUTSCRIPT \"liveimap\" {6+}"),
            transport.written,
        )
        val bytes = script.toByteArray(StandardCharsets.UTF_8)
        assertArrayEquals(bytes, transport.writtenBytes[0])
        assertArrayEquals(bytes, transport.writtenBytes[1])
        assertTrue(transport.written.none { it.startsWith("SETACTIVE") })
    }

    @Test
    fun uploadStopsWhenCheckFails() = runBlocking {
        val transport = ListTransport(listOf("NO \"line 1\""))
        try {
            uploadLiveimap(transport, "bad;")
            fail("expected SieveFailure")
        } catch (failure: SieveFailure) {
            assertEquals("line 1", failure.text)
        }
        assertEquals(listOf("CHECKSCRIPT {4+}"), transport.written)
        assertTrue(transport.written.none { it.startsWith("PUTSCRIPT") })
        assertEquals(1, transport.writtenBytes.size)
        assertEquals(4, transport.writtenBytes.single().size)
    }

    @Test
    fun setActiveQuotesTheName() = runBlocking {
        val transport = ListTransport(listOf("OK"))
        assertEquals("", setActive(transport, "my \"script\""))
        assertEquals(listOf("SETACTIVE \"my \\\"script\\\"\""), transport.written)
    }

    @Test
    fun setActiveRefusesAnEmptyName() = runBlocking {
        val transport = ListTransport(emptyList())
        try {
            setActive(transport, "")
            fail("expected SieveFailure")
        } catch (failure: SieveFailure) {
            assertEquals("name", failure.text)
        }
        assertEquals(emptyList<String>(), transport.written)
        assertTrue(transport.writtenBytes.isEmpty())
    }

    @Test
    fun setActiveUsesTheServerText() = runBlocking {
        val transport = ListTransport(listOf("NO \"busy\""))
        try {
            setActive(transport, "liveimap")
            fail("expected SieveFailure")
        } catch (failure: SieveFailure) {
            assertEquals("busy", failure.text)
        }
        assertEquals(listOf("SETACTIVE \"liveimap\""), transport.written)
    }

    @Test
    fun refusedConsentWritesNothing() = runBlocking {
        val include = LiveimapActivatePlan(
            LiveimapActivateAction.Include,
            "vacation",
            "keep;\n$liveimapIncludeLine\n",
        )
        val includeTransport = ListTransport(emptyList())
        try {
            putConsentedInclude(includeTransport, include, consent = false)
            fail("expected SieveFailure")
        } catch (failure: SieveFailure) {
            assertEquals("consent", failure.text)
        }
        assertEquals(emptyList<String>(), includeTransport.written)
        assertTrue(includeTransport.writtenBytes.isEmpty())

        val active = LiveimapActivatePlan(LiveimapActivateAction.SetActive, "", "")
        val activeTransport = ListTransport(emptyList())
        try {
            activateConsented(activeTransport, active, consent = false)
            fail("expected SieveFailure")
        } catch (failure: SieveFailure) {
            assertEquals("consent", failure.text)
        }
        assertEquals(emptyList<String>(), activeTransport.written)
        assertTrue(activeTransport.writtenBytes.isEmpty())
    }

    @Test
    fun includeRewriteRefusesTheWrongPlan() = runBlocking {
        val plans = listOf(
            LiveimapActivatePlan(LiveimapActivateAction.SetActive, "vacation", "keep;\n"),
            LiveimapActivatePlan(LiveimapActivateAction.Include, "", "keep;\n"),
            LiveimapActivatePlan(LiveimapActivateAction.Include, "liveimap", "keep;\n"),
            LiveimapActivatePlan(LiveimapActivateAction.None, "vacation", "keep;\n"),
        )
        for (plan in plans) {
            val transport = ListTransport(listOf("OK", "OK"))
            try {
                putConsentedInclude(transport, plan, consent = true)
                fail("expected SieveFailure")
            } catch (failure: SieveFailure) {
                assertEquals("consent", failure.text)
            }
            assertEquals(emptyList<String>(), transport.written)
            assertTrue(transport.writtenBytes.isEmpty())
        }
    }

    @Test
    fun includeRewritePutsOnlyTheActiveScript() = runBlocking {
        val rewritten = "keep;\n$liveimapIncludeLine\n"
        val plan = LiveimapActivatePlan(LiveimapActivateAction.Include, "vacation", rewritten)
        val transport = ListTransport(listOf("OK", "OK"))
        assertEquals("", putConsentedInclude(transport, plan, consent = true))
        assertEquals(
            listOf("CHECKSCRIPT {36+}", "PUTSCRIPT \"vacation\" {36+}"),
            transport.written,
        )
        val bytes = rewritten.toByteArray(StandardCharsets.UTF_8)
        assertEquals(36, bytes.size)
        assertArrayEquals(bytes, transport.writtenBytes[0])
        assertArrayEquals(bytes, transport.writtenBytes[1])
    }

    @Test
    fun includeRewriteStopsWhenCheckFails() = runBlocking {
        val plan = LiveimapActivatePlan(LiveimapActivateAction.Include, "vacation", "bad;")
        val transport = ListTransport(listOf("NO \"line 1\""))
        try {
            putConsentedInclude(transport, plan, consent = true)
            fail("expected SieveFailure")
        } catch (failure: SieveFailure) {
            assertEquals("line 1", failure.text)
        }
        assertEquals(listOf("CHECKSCRIPT {4+}"), transport.written)
        assertTrue(transport.written.none { it.startsWith("PUTSCRIPT") })
    }

    @Test
    fun activateRefusesUnlessSetActive() = runBlocking {
        val plan = LiveimapActivatePlan(LiveimapActivateAction.Include, "vacation", "keep;\n")
        val transport = ListTransport(listOf("OK"))
        try {
            activateConsented(transport, plan, consent = true)
            fail("expected SieveFailure")
        } catch (failure: SieveFailure) {
            assertEquals("consent", failure.text)
        }
        assertEquals(emptyList<String>(), transport.written)
    }

    @Test
    fun activateSetsLiveimap() = runBlocking {
        val plan = planLiveimapActivation(emptyList(), emptyList(), "keep;")
        assertEquals(LiveimapActivateAction.SetActive, plan.action)
        assertEquals("", plan.activeName)
        val transport = ListTransport(listOf("OK"))
        assertEquals("", activateConsented(transport, plan, consent = true))
        assertEquals(listOf("SETACTIVE \"liveimap\""), transport.written)
        assertTrue(transport.writtenBytes.isEmpty())
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
