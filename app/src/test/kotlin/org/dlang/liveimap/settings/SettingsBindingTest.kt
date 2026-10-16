package org.dlang.liveimap.settings

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsBindingTest {
    @Test
    fun serverCapAllowsWithoutASessionOrOnJmap() {
        assertTrue(serverCapAllows(jmapFolders = false, sessionOpen = false, advertised = false))
        assertTrue(serverCapAllows(jmapFolders = true, sessionOpen = true, advertised = false))
        assertFalse(serverCapAllows(jmapFolders = false, sessionOpen = true, advertised = false))
    }

    @Test
    fun sievePinUsesTheCapturedStore() {
        val screen = source("kotlin/org/dlang/liveimap/settings/SettingsScreen.kt")
        val greet = screen.indexOf("greetSieve(")
        val before = screen.substring(0, greet)
        assertTrue(before.contains("val sieveStore = editor.bound"))
        assertTrue(before.contains("val sieveAccount = editor.editingId"))
        val callback = screen.substring(screen.indexOf("fingerprint ->", greet))
        val call = "persistSievePin(sieveStore, sieveAccount, fingerprint)"
        val body = callback.substring(0, callback.indexOf(call) + call.length)
        assertTrue(body.contains("persistSievePin"))
        assertFalse(body.contains("editor.persist"))
        assertFalse(body.contains("editor.settings"))
        val deleting = screen.substring(screen.indexOf("settings_deleting"), screen.indexOf("settings_counts"))
        assertTrue(deleting.contains("existingMailSession"))
        assertFalse(deleting.contains("mailSession("))
    }

    @Test
    fun passwordDraftIsNotSaveable() {
        val screen = source("kotlin/org/dlang/liveimap/settings/SettingsScreen.kt")
        val line = screen.substring(screen.indexOf("private fun LineField("), screen.indexOf("private fun AskLimitField"))
        assertTrue(line.contains("if (password)"))
        assertTrue(line.contains("remember { mutableStateOf(value) }"))
        assertTrue(line.contains("rememberSaveable { mutableStateOf(value) }"))
        assertTrue(screen.contains("var passwordDraft by remember("))
        assertTrue(screen.contains("var password by remember { mutableStateOf(\"\") }"))
        assertTrue(screen.contains("var denied by rememberSaveable"))
    }

    @Test
    fun preferenceWriteStaysInsideTheGate() {
        val store = source("kotlin/org/dlang/liveimap/accounts/AccountStore.kt")
        for (name in listOf("save", "update", "setPassword")) {
            val start = store.indexOf("suspend fun $name")
            val next = store.indexOf("\n    suspend fun ", start + 1)
            val body = store.substring(start, next)
            val lock = body.indexOf("gate.withLock")
            val write = body.indexOf("writePreferences()")
            val close = body.lastIndexOf("\n    }")
            assertTrue("$name inside", lock >= 0 && write > lock && write < close)
            assertTrue("$name not after", body.indexOf("writePreferences()", close) < 0)
        }
        val removal = store.substring(store.indexOf("fun deleteAccountArtifacts"))
        val drop = removal.indexOf("dropMailSession")
        val files = removal.indexOf("removeAccountFiles")
        assertTrue(drop in 0 until files)
    }

    @Test
    fun filterUploadUsesTheShownScriptAndAccount() {
        val filter = source("kotlin/org/dlang/liveimap/ui/filter/FilterScreen.kt")
        assertFalse(filter.contains("Mutex"))
        val scriptAt = filter.indexOf("val script = emitSieve(loaded)")
        assertTrue(scriptAt > 0)
        val click = filter.substring(filter.indexOf("onClick = {", scriptAt))
        val clickBody = click.substring(0, click.indexOf("enabled = !uploading"))
        assertTrue(clickBody.contains("script"))
        assertFalse(clickBody.contains("emitSieve"))
        val nav = source("kotlin/org/dlang/liveimap/ui/NavHost.kt")
        val open = nav.substring(nav.indexOf("fun openFilterEditor"))
        val openBody = open.substring(0, open.indexOf("fun openCompose"))
        assertTrue(openBody.contains("filterAccountId.value = accountId"))
        assertTrue(openBody.contains("filterRuleId.value = ruleId"))
        val reader = source("kotlin/org/dlang/liveimap/ui/reader/MessageReaderScreen.kt")
        assertTrue(reader.contains("onFilterLike(headerFrom, headerTo, listIdKey(headerListId), headerSubject, accountId)"))
    }

    @Test
    fun sieveTlsIsPassedAndAPreservedLineStopsTheCall() {
        val screen = source("kotlin/org/dlang/liveimap/settings/SettingsScreen.kt")
        val greet = screen.indexOf("greetSieve(")
        assertTrue(screen.indexOf("preservedSieveTlsKey()") in 0 until greet)
        val greetCall = screen.substring(greet, screen.indexOf("persistSievePin", greet))
        assertTrue(greetCall.contains("sieveTlsMode"))
        val choice = screen.indexOf("settings_sieve_tls")
        assertTrue(screen.indexOf("withoutPreserved(\"sieveTlsMode\")", choice) > choice)
        val filter = source("kotlin/org/dlang/liveimap/ui/filter/FilterScreen.kt")
        val upload = filter.indexOf("openAndDeliverLiveimap(")
        assertTrue(filter.indexOf("preservedSieveTlsKey()") in 0 until upload)
        val uploadCall = filter.substring(upload, filter.indexOf(") { plan ->", upload))
        assertTrue(uploadCall.contains("sieveTlsMode"))
        val reader = source("kotlin/org/dlang/liveimap/ui/reader/MessageReaderScreen.kt")
        assertTrue(reader.contains("onFilterLike(headerFrom, headerTo, listIdKey(headerListId), headerSubject, accountId)"))
    }

    @Test
    fun readerCapsHtmlAndDoesNotTrustImagesOnOpen() {
        val reader = source("kotlin/org/dlang/liveimap/ui/reader/MessageReaderScreen.kt")
        assertFalse(reader.contains("val gate"))
        assertFalse(reader.contains("gate.withLock"))
        assertTrue(reader.contains("saveMutex"))
        assertTrue(reader.contains("saveMutex.withLock"))
        assertFalse(reader.contains("imagesTrusted("))
        assertTrue(reader.contains("allowImages = !allowImages"))
        assertTrue(reader.contains("if (!on) allowImages = true"))
        val html = reader.substring(reader.indexOf("BodyView.PlainOrHtml ->"), reader.indexOf("BodyView.PlainOrText ->"))
        val text = reader.substring(reader.indexOf("BodyView.PlainOrText ->"), reader.indexOf("BodyView.Headers, BodyView.Raw ->"))
        val call = "readPart(html, markSeen = !seenStored, RawViewCapBytes)"
        assertTrue(html.contains(call))
        assertTrue(text.contains(call))
        assertTrue(html.contains("bodyTruncated = truncated"))
        assertTrue(text.contains("bodyTruncated = truncated"))
        val read = reader.substring(reader.indexOf("suspend fun readPart("), reader.indexOf("fun showMissing("))
        assertTrue(read.contains("out.size() < cap"))
        assertTrue(read.contains("cap - out.size()"))
        val rendered = reader.substring(
            reader.indexOf("absent == null && renderedHtml ->"),
            reader.indexOf("absent == null && (selectedView == BodyView.Headers"),
        )
        assertTrue(rendered.contains("if (bodyTruncated) Text(stringResource(R.string.reader_raw_truncated, RawViewCapBytes / 1024)"))
        val asText = reader.substring(reader.indexOf("absent == null ->"), reader.indexOf("else -> {"))
        assertTrue(asText.contains("if (bodyTruncated && selectedView == BodyView.PlainOrText)"))
        assertTrue(asText.contains("stringResource(R.string.reader_raw_truncated, RawViewCapBytes / 1024)"))
    }

    @Test
    fun newerReaderViewWinsAndCutHtmlUsesTake() {
        val reader = source("kotlin/org/dlang/liveimap/ui/reader/MessageReaderScreen.kt")
        for (bounds in listOf(
            "BodyView.PlainOrHtml ->" to "BodyView.PlainOrText ->",
            "BodyView.PlainOrText ->" to "BodyView.Headers, BodyView.Raw ->",
        )) {
            val section = reader.substring(reader.indexOf(bounds.first), reader.indexOf(bounds.second))
            val mark = section.indexOf("if (truncated)")
            assertTrue(mark > 0)
            val truncated = section.substring(mark, section.indexOf("} else {", mark))
            assertTrue(truncated.contains("WireTextDecoder"))
            assertTrue(truncated.contains(".take("))
            assertFalse(truncated.contains("decodePart("))
            assertFalse(truncated.contains("finish"))
            val other = section.substring(section.indexOf("} else {", mark), section.indexOf("bodyTruncated = truncated"))
            assertTrue(other.contains("decodePart("))
        }
        val request = reader.substring(reader.indexOf("fun requestView("), reader.indexOf("fun markAttachment("))
        val launch = request.indexOf("scope.launch")
        val requested = request.indexOf("nextBodyLoad()")
        assertTrue(requested in 0 until launch)
        assertTrue(request.indexOf("loadPreferred(") > requested)
        assertTrue(request.indexOf("pullUnbounded(") > requested)
        val open = reader.substring(
            reader.indexOf("LaunchedEffect(session, mailbox, uid, loadToken)"),
            reader.indexOf("LaunchedEffect(scroll)"),
        )
        val opened = open.indexOf("nextBodyLoad()")
        assertTrue(opened > 0)
        assertTrue(opened == open.lastIndexOf("nextBodyLoad()"))
        assertTrue(opened < open.indexOf("session.openBound"))
        assertTrue(opened < open.indexOf("loadPreferred("))
        assertTrue(opened < open.indexOf("pullUnbounded("))
        fun afterPeek(start: String, end: String) {
            val body = reader.substring(reader.indexOf(start), reader.indexOf(end))
            var from = 0
            var peeks = 0
            while (true) {
                val peek = body.indexOf("peekRef(", from)
                if (peek < 0) break
                peeks += 1
                val next = body.indexOf("peekRef(", peek + 1)
                val slice = body.substring(peek, if (next < 0) body.length else next)
                assertTrue(slice.indexOf("token != bodyGeneration") > 0)
                from = peek + "peekRef(".length
            }
            assertTrue(peeks > 0)
        }
        afterPeek("suspend fun pullBody(", "suspend fun pullUnbounded(")
        afterPeek("suspend fun pullUnbounded(", "suspend fun readPart(")
        afterPeek("suspend fun readPart(", "fun showMissing(")
        val scroll = reader.substring(
            reader.indexOf("LaunchedEffect(connected, bodySection)"),
            reader.indexOf("fun nextAfterDeleteOrMove"),
        )
        assertFalse(scroll.contains("nextBodyLoad"))
        assertTrue(scroll.contains("pageGeneration"))
        assertTrue(scroll.contains("pullBody("))
        assertTrue(reader.contains("pageGeneration = token"))
        assertFalse(reader.contains("val gate"))
        assertFalse(reader.contains("gate.withLock"))
    }

    private fun source(path: String): String =
        listOf("src/main/$path", "app/src/main/$path").map(::File).first { it.exists() }.readText()
}
