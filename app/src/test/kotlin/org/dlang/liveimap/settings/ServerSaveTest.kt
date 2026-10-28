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

    @Test fun jmapNullCheckIsNotUnreachable() {
        val draft = newAccountSettings().copy(jmapFolders = true)
        val review = serverSaveReview(draft, null)
        assertFalse(review.unreachable)
        assertTrue(review.quiet)
        val plain = serverSaveReview(draft.withTlsMode(TlsMode.None), null)
        assertNotNull(plain.tlsWarning)
        assertFalse(plain.unreachable)
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
        } finally {
            UnsavedEdits.pending = false
            UnsavedEdits.ask = null
        }
    }
}
