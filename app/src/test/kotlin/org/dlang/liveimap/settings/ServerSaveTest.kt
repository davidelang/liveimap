package org.dlang.liveimap.settings

import java.io.File
import org.dlang.liveimap.engine.ServerCheck
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerSaveTest {
    private val ok = ServerCheck(reachable = true, conflicts = emptyList())

    @Test fun tlsOffWarnsOnceAtSave() {
        val draft = newAccountSettings().withTlsMode(TlsMode.None)
        val review = serverSaveReview(draft, ok)
        assertNotNull(review.tlsWarning)
        assertFalse(review.quiet)
        assertTrue(review.lines.isEmpty())
    }

    @Test fun cleanTlsDraftSavesQuietly() {
        assertTrue(serverSaveReview(newAccountSettings(), ok).quiet)
    }

    @Test fun failedCheckIsReportedNotReverted() {
        val draft = newAccountSettings().copy(imapHost = "smtp.example")
        val review = serverSaveReview(draft, null)
        assertTrue(review.unreachable)
        assertEquals("smtp.example", draft.imapHost)
    }

    @Test fun conflictsAndNotesListed() {
        val review = serverSaveReview(newAccountSettings(), ServerCheck(true, listOf("c"), listOf("n")))
        assertEquals(listOf("c", "n"), review.lines)
    }

    @Test fun draftEqualToSavedIsNotADraft() {
        val saved = newAccountSettings()
        assertTrue(sameServer(saved, saved.copy(sieveHost = "x")))
        assertFalse(sameServer(saved.copy(imapHost = "h"), saved))
    }

    private fun screen() = listOf("src/main/kotlin/org/dlang/liveimap/settings/SettingsScreen.kt", "app/src/main/kotlin/org/dlang/liveimap/settings/SettingsScreen.kt")
        .map(::File).first { it.exists() }.readText()

    @Test fun noRevertAndNoMidEditCheck() {
        val s = screen()
        assertFalse(s.contains("settings_check_revert"))
        assertFalse(s.contains("delay(1500)"))
        assertFalse(s.contains("\"no-tls-warning\""))
        assertTrue(s.contains("BackHandler(enabled = hasDraft)"))
        assertTrue(s.contains("dismissOnClickOutside = false"))
    }

    /** David: a typed password enables Save and is stored by Save, not per keystroke. */
    @Test fun passwordsArePartOfTheDraft() {
        val s = screen()
        assertTrue(s.contains("val hasDraft = serverDraft != null || passwordDraft != null || smtpPasswordDraft != null"))
        assertTrue(s.contains("enabled = hasDraft && !serverChecking"))
        assertTrue(s.contains("{ passwordDraft = it.takeIf { typed -> typed != editor.password } }"))
        assertTrue(s.contains("{ smtpPasswordDraft = it.takeIf { typed -> typed != editor.smtpPassword } }"))
        assertFalse(s.contains(") { editor.persistPassword(it) }"))
        assertFalse(s.contains(") { editor.persistSmtpPassword(it) }"))
        assertTrue(s.contains("val draft = serverDraft ?: return commitPasswords()"))
    }

    @Test fun noPlainTextButtons() {
        val root = listOf("src/main/kotlin", "app/src/main/kotlin").map(::File).first { it.exists() }
        val offenders = root.walk().filter { it.extension == "kt" }
            .filter { it.readText().contains("material3.TextButton") }.map { it.name }.toList()
        assertNull(offenders.takeIf { it.isNotEmpty() })
    }

    @Test
    fun drawerNavigationWaitsForTheUnsavedEditsAnswer() {
        var went = 0
        var held: (() -> Unit)? = null
        try {
            UnsavedEdits.pending = false
            UnsavedEdits.ask = { go -> held = go }
            UnsavedEdits.guard { went++ }
            org.junit.Assert.assertEquals(1, went) // nothing pending: navigate at once
            UnsavedEdits.pending = true
            UnsavedEdits.guard { went++ }
            org.junit.Assert.assertEquals(1, went) // pending: the screen asks first
            held!!.invoke() // Save or Discard
            org.junit.Assert.assertEquals(2, went)
            UnsavedEdits.ask = null
            UnsavedEdits.guard { went++ }
            org.junit.Assert.assertEquals(3, went) // no screen to ask (left already): navigate
            val nav = java.io.File("src/main/kotlin/org/dlang/liveimap/ui/NavHost.kt").readText()
            val sheet = nav.substring(nav.indexOf("private fun ColumnScope.DrawerSheetContent("))
            val body = sheet.substring(0, sheet.indexOf("\n}\n"))
            val clicks = Regex("""(onClick|onLongClick) = (\{[^\n]*|on\w+)""").findAll(body).map { it.value }
                .filter { it != "onClick = {}," && !it.endsWith("= {}") && it != "onClick = {}" }.toList()
            org.junit.Assert.assertTrue(clicks.isNotEmpty())
            for (click in clicks) org.junit.Assert.assertTrue(click, click.contains("UnsavedEdits.guard"))
        } finally {
            UnsavedEdits.pending = false
            UnsavedEdits.ask = null
        }
    }
}
