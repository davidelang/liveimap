package org.dlang.liveimap.engine.sieve

import java.nio.charset.StandardCharsets
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
    fun startTlsOkWritesTheCommand() = runBlocking {
        val plain = ListTransport(listOf("OK", "\"STARTTLS\""))
        requestStartTls(plain)
        assertEquals(listOf("STARTTLS"), plain.written)
        assertEquals(listOf("OK"), plain.read)
        assertTrue(plain.writtenBytes.isEmpty())

        val text = ListTransport(listOf("OK \"ready\"", "OK"))
        requestStartTls(text)
        assertEquals(listOf("STARTTLS"), text.written)
        assertEquals(listOf("OK \"ready\""), text.read)
        assertTrue(text.written.none { it.contains("AUTHENTICATE") })
    }

    @Test
    fun startTlsNoThrows() = runBlocking {
        val refused = ListTransport(listOf("NO \"refused\"", "OK"))
        try {
            requestStartTls(refused)
            fail("expected SieveFailure")
        } catch (failure: SieveFailure) {
            assertEquals("refused", failure.text)
        }
        assertEquals(listOf("STARTTLS"), refused.written)
        assertEquals(listOf("NO \"refused\""), refused.read)

        val bare = ListTransport(listOf("NO"))
        try {
            requestStartTls(bare)
            fail("expected SieveFailure")
        } catch (failure: SieveFailure) {
            assertEquals("NO", failure.text)
        }

        val bye = ListTransport(listOf("BYE \"gone\""))
        try {
            requestStartTls(bye)
            fail("expected SieveFailure")
        } catch (failure: SieveFailure) {
            assertEquals("gone", failure.text)
        }
        assertEquals(listOf("BYE \"gone\""), bye.read)
    }

    @Test
    fun missingStartTlsThrows() = runBlocking {
        val transport = ListTransport(emptyList())
        try {
            requireAdvertisedStartTls(SieveCapabilities(startTls = false))
            fail("expected SieveFailure")
        } catch (failure: SieveFailure) {
            assertEquals("STARTTLS is not advertised", failure.text)
        }
        assertEquals(emptyList<String>(), transport.written)
        assertTrue(transport.writtenBytes.isEmpty())
        requireAdvertisedStartTls(SieveCapabilities(startTls = true))
        assertEquals(emptyList<String>(), transport.written)
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

    @Test
    fun choosesCramMd5BeforePlain() {
        assertEquals(
            "CRAM-MD5",
            chooseSieveSasl(listOf("plain", "Cram-Md5"), plaintextOk = false),
        )
        assertEquals(
            "CRAM-MD5",
            chooseSieveSasl(listOf("SCRAM-SHA-256 PLAIN", "CRAM-MD5"), plaintextOk = true),
        )
        assertEquals("PLAIN", chooseSieveSasl(listOf("LOGIN", "plain"), plaintextOk = true))
        assertEquals("PLAIN", chooseSieveSasl(listOf("SCRAM-SHA-256 PLAIN"), plaintextOk = true))
        assertNull(chooseSieveSasl(listOf("PLAIN"), plaintextOk = false))
        assertNull(
            chooseSieveSasl(
                listOf("SCRAM-SHA-256", "LOGIN", "DIGEST-MD5", "CRAM-MD5-PLUS", "APLAIN"),
                plaintextOk = true,
            ),
        )
        assertNull(chooseSieveSasl(emptyList(), plaintextOk = true))
    }

    @Test
    fun saslVectorsHideThePassword() {
        assertEquals("AGFkYQBzZWNyZXQ=", plainSaslInitial("ada", "secret"))
        val challenge = "<1234@example.com>".toByteArray(StandardCharsets.UTF_8)
        assertEquals(
            "YWRhIDJiNjhkODE1ZDI3MTM4ZTQ0ODk1Nzc5ZmI3MThiZDM3",
            cramMd5Response("ada", "secret", challenge),
        )
    }

    @Test
    fun emptySaslWritesNothing() = runBlocking {
        val plainUser = ListTransport(emptyList())
        try {
            authenticatePlain(plainUser, "", "secret")
            fail("expected SieveFailure")
        } catch (failure: SieveFailure) {
            assertEquals("sasl", failure.text)
        }
        assertEquals(emptyList<String>(), plainUser.written)

        val plainPassword = ListTransport(emptyList())
        try {
            authenticatePlain(plainPassword, "ada", "")
            fail("expected SieveFailure")
        } catch (failure: SieveFailure) {
            assertEquals("sasl", failure.text)
        }
        assertEquals(emptyList<String>(), plainPassword.written)

        val cramUser = ListTransport(emptyList())
        try {
            authenticateCramMd5(cramUser, "", "secret")
            fail("expected SieveFailure")
        } catch (failure: SieveFailure) {
            assertEquals("sasl", failure.text)
        }
        assertEquals(emptyList<String>(), cramUser.written)

        val cramPassword = ListTransport(emptyList())
        try {
            authenticateCramMd5(cramPassword, "ada", "")
            fail("expected SieveFailure")
        } catch (failure: SieveFailure) {
            assertEquals("sasl", failure.text)
        }
        assertEquals(emptyList<String>(), cramPassword.written)
    }

    @Test
    fun plainAuthenticateUsesTheServerText() = runBlocking {
        val ok = ListTransport(listOf("OK \"ready\""))
        assertEquals("ready", authenticatePlain(ok, "ada", "secret"))
        assertEquals(
            listOf("AUTHENTICATE \"PLAIN\" \"AGFkYQBzZWNyZXQ=\""),
            ok.written,
        )
        assertTrue(ok.written.none { it.contains("secret") })

        val no = ListTransport(listOf("NO \"no sasl\""))
        try {
            authenticatePlain(no, "ada", "secret")
            fail("expected SieveFailure")
        } catch (failure: SieveFailure) {
            assertEquals("no sasl", failure.text)
        }
        assertEquals(
            listOf("AUTHENTICATE \"PLAIN\" \"AGFkYQBzZWNyZXQ=\""),
            no.written,
        )
        assertTrue(no.written.none { it.contains("secret") })
    }

    @Test
    fun cramAuthenticateUsesQuotedAndLiteralChallenges() = runBlocking {
        val quoted = ListTransport(
            listOf("\"PDEyMzRAZXhhbXBsZS5jb20+\"", "OK \"in\""),
        )
        assertEquals("in", authenticateCramMd5(quoted, "ada", "secret"))
        assertEquals(
            listOf(
                "AUTHENTICATE \"CRAM-MD5\"",
                "\"YWRhIDJiNjhkODE1ZDI3MTM4ZTQ0ODk1Nzc5ZmI3MThiZDM3\"",
            ),
            quoted.written,
        )
        assertTrue(quoted.written.none { it.contains("secret") })

        val encoded = "PDEyMzRAZXhhbXBsZS5jb20+"
        val literal = ListTransport(
            listOf("{${encoded.length}}", "OK"),
            listOf(encoded.toByteArray(StandardCharsets.UTF_8)),
        )
        assertEquals("", authenticateCramMd5(literal, "ada", "secret"))
        assertEquals(
            listOf(
                "AUTHENTICATE \"CRAM-MD5\"",
                "\"YWRhIDJiNjhkODE1ZDI3MTM4ZTQ0ODk1Nzc5ZmI3MThiZDM3\"",
            ),
            literal.written,
        )
        assertTrue(literal.written.none { it.contains("secret") })

        val denied = ListTransport(listOf("NO \"denied\""))
        try {
            authenticateCramMd5(denied, "ada", "secret")
            fail("expected SieveFailure")
        } catch (failure: SieveFailure) {
            assertEquals("denied", failure.text)
        }
        assertEquals(listOf("AUTHENTICATE \"CRAM-MD5\""), denied.written)
        assertTrue(denied.written.none { it.contains("secret") })

        val later = ListTransport(listOf("\"PDEyMzRAZXhhbXBsZS5jb20+\"", "NO \"later\""))
        try {
            authenticateCramMd5(later, "ada", "secret")
            fail("expected SieveFailure")
        } catch (failure: SieveFailure) {
            assertEquals("later", failure.text)
        }
        assertEquals(2, later.written.size)
        assertTrue(later.written.none { it.contains("secret") })
    }

    @Test
    fun uploadAuthenticatesWithCramThenPutsLiveimap() = runBlocking {
        val script = "keep;\n"
        val transport = ListTransport(
            listOf(
                "\"PDEyMzRAZXhhbXBsZS5jb20+\"",
                "OK",
                "OK",
                "OK (WARNINGS) \"note\"",
                "OK",
            ),
        )
        val warning = authenticateAndUpload(
            transport,
            SieveCapabilities(sasl = listOf("CRAM-MD5", "PLAIN")),
            script,
            "ada",
            "secret",
            plaintextOk = false,
        )
        assertEquals("note", warning)
        assertEquals(
            listOf(
                "AUTHENTICATE \"CRAM-MD5\"",
                "\"YWRhIDJiNjhkODE1ZDI3MTM4ZTQ0ODk1Nzc5ZmI3MThiZDM3\"",
                "CHECKSCRIPT {6+}",
                "PUTSCRIPT \"liveimap\" {6+}",
                "LOGOUT",
            ),
            transport.written,
        )
        assertTrue(transport.written.none { it.startsWith("SETACTIVE") })
    }

    @Test
    fun uploadAuthenticatesWithPlainWhenPlaintextIsAllowed() = runBlocking {
        val script = "keep;\n"
        val transport = ListTransport(listOf("OK", "OK", "OK", "OK"))
        assertEquals(
            "",
            authenticateAndUpload(
                transport,
                SieveCapabilities(sasl = listOf("PLAIN")),
                script,
                "ada",
                "secret",
                plaintextOk = true,
            ),
        )
        assertEquals(
            listOf(
                "AUTHENTICATE \"PLAIN\" \"AGFkYQBzZWNyZXQ=\"",
                "CHECKSCRIPT {6+}",
                "PUTSCRIPT \"liveimap\" {6+}",
                "LOGOUT",
            ),
            transport.written,
        )
        assertTrue(transport.written.none { it.startsWith("SETACTIVE") })
    }

    @Test
    fun uploadRefusesPlainWhenPlaintextIsOff() = runBlocking {
        val transport = ListTransport(emptyList())
        try {
            authenticateAndUpload(
                transport,
                SieveCapabilities(sasl = listOf("PLAIN")),
                "keep;\n",
                "ada",
                "secret",
                plaintextOk = false,
            )
            fail("expected SieveFailure")
        } catch (failure: SieveFailure) {
            assertEquals("sasl", failure.text)
        }
        assertEquals(emptyList<String>(), transport.written)
        assertTrue(transport.writtenBytes.isEmpty())
    }

    @Test
    fun uploadRefusesAnUnchosenMechanism() = runBlocking {
        val transport = ListTransport(emptyList())
        try {
            authenticateAndUpload(
                transport,
                SieveCapabilities(sasl = listOf("OAUTHBEARER")),
                "keep;\n",
                "ada",
                "secret",
                plaintextOk = true,
            )
            fail("expected SieveFailure")
        } catch (failure: SieveFailure) {
            assertEquals("sasl", failure.text)
        }
        assertEquals(emptyList<String>(), transport.written)
        assertTrue(transport.writtenBytes.isEmpty())
    }

    @Test
    fun uploadRefusesAnEmptyUsername() = runBlocking {
        val transport = ListTransport(emptyList())
        try {
            authenticateAndUpload(
                transport,
                SieveCapabilities(sasl = listOf("PLAIN")),
                "keep;\n",
                "",
                "secret",
                plaintextOk = true,
            )
            fail("expected SieveFailure")
        } catch (failure: SieveFailure) {
            assertEquals("sasl", failure.text)
        }
        assertEquals(emptyList<String>(), transport.written)
        assertTrue(transport.writtenBytes.isEmpty())
    }

    @Test
    fun uploadStopsWhenCheckScriptFails() = runBlocking {
        val transport = ListTransport(listOf("OK", "NO \"bad\""))
        try {
            authenticateAndUpload(
                transport,
                SieveCapabilities(sasl = listOf("PLAIN")),
                "keep;\n",
                "ada",
                "secret",
                plaintextOk = true,
            )
            fail("expected SieveFailure")
        } catch (failure: SieveFailure) {
            assertEquals("bad", failure.text)
        }
        assertEquals(
            listOf(
                "AUTHENTICATE \"PLAIN\" \"AGFkYQBzZWNyZXQ=\"",
                "CHECKSCRIPT {6+}",
            ),
            transport.written,
        )
        assertTrue(transport.written.none { it.startsWith("PUTSCRIPT") })
        assertTrue(transport.written.none { it.startsWith("SETACTIVE") })
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
