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
        assertTrue(s.contains("BackHandler(enabled = serverDraft != null)"))
        assertTrue(s.contains("dismissOnClickOutside = false"))
    }

    @Test fun noPlainTextButtons() {
        val root = listOf("src/main/kotlin", "app/src/main/kotlin").map(::File).first { it.exists() }
        val offenders = root.walk().filter { it.extension == "kt" }
            .filter { it.readText().contains("material3.TextButton") }.map { it.name }.toList()
        assertNull(offenders.takeIf { it.isNotEmpty() })
    }
}
