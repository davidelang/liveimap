package org.dlang.liveimap.engine

import org.dlang.liveimap.accounts.secretStoreUsable
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** F03 dialogs, F96 and F99: source checks plus the Keystore gate. */
class AddendaGuardTest {
    private val cpp = File("src/main/cpp/liveimap_jni.cpp").readText()

    private fun esearchBody(): String {
        val start = cpp.indexOf("int parseEsearch(")
        assertTrue(start >= 0)
        return cpp.substring(start, cpp.indexOf("\n}\n", start))
    }

    @Test
    fun esearchForAnotherCommandIsDropped() {
        val body = esearchBody()
        assertTrue(body.contains("tag != currentTagText(tlsImap)"))
        assertTrue(body.contains("ignored->ext_type = -1"))
    }

    @Test
    fun starInAMailboxScopedResultIsDropped() {
        val body = esearchBody()
        assertTrue(body.contains("tlsSeqSawStar = false"))
        assertTrue(body.contains("if (sawMailbox && tlsSeqSawStar)"))
        assertTrue(body.indexOf("if (sawMailbox && tlsSeqSawStar)") < body.indexOf("calloc(1, sizeof(LiveScopeHit))"))
    }

    @Test
    fun secretsNeedTheKeystore() {
        assertFalse(secretStoreUsable(false))
        assertTrue(secretStoreUsable(true))
        val box = File("src/main/kotlin/org/dlang/liveimap/accounts/SecretBox.kt").readText()
        assertTrue(box.contains("requireKeystore()"))
    }

    @Test
    fun setupDialogsHideOverlays() {
        val auth = File("src/main/kotlin/org/dlang/liveimap/accounts/AuthenticatorService.kt").readText()
        assertTrue(auth.contains("setHideOverlayWindows(true)"))
        assertTrue(File("src/main/AndroidManifest.xml").readText().contains("HIDE_OVERLAY_WINDOWS"))
    }

    @Test
    fun settingsSaveFailuresAreShown() {
        val s = File("src/main/kotlin/org/dlang/liveimap/settings/SettingsScreen.kt").readText()
        assertTrue(s.contains("CoroutineExceptionHandler"))
    }
}
