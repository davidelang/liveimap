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

    private fun source(path: String): String =
        listOf("src/main/$path", "app/src/main/$path").map(::File).first { it.exists() }.readText()
}
