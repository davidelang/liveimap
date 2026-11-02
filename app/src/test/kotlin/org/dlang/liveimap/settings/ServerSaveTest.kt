package org.dlang.liveimap.settings

import java.io.File
import org.dlang.liveimap.accounts.accountUserData
import org.dlang.liveimap.accounts.overlayAccountFields
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

    @Test fun saveOutcomesAreStoreHoldAndConfirmedStore() {
        val quiet = serverSaveReview(newAccountSettings(), ok)
        assertEquals(SettingsSaveChoice.Store, settingsSaveChoice(confirmed = false, review = quiet))
        assertEquals(
            "settings save account-save account=acct stored",
            settingsSaveReport("account-save", "acct", "stored"),
        )
        val held = serverSaveReview(newAccountSettings(), null)
        assertTrue(held.unreachable)
        assertNull(held.tlsWarning)
        assertEquals(SettingsSaveChoice.Hold, settingsSaveChoice(confirmed = false, review = held))
        assertEquals(
            "settings save settings-save account=acct held unreachable=true tls=false",
            settingsSaveReport("settings-save", "acct", settingsSaveHeld(held)),
        )
        val tls = serverSaveReview(newAccountSettings().withTlsMode(TlsMode.None), ok)
        assertNotNull(tls.tlsWarning)
        assertEquals(SettingsSaveChoice.Hold, settingsSaveChoice(confirmed = false, review = tls))
        assertEquals(
            "settings save account-save account=acct held unreachable=false tls=true",
            settingsSaveReport("account-save", "acct", settingsSaveHeld(tls)),
        )
        assertEquals(SettingsSaveChoice.Store, settingsSaveChoice(confirmed = true, review = held))
        assertEquals(SettingsSaveChoice.Store, settingsSaveChoice(confirmed = true, review = tls))
        assertEquals(
            "settings save unsaved account=acct stored",
            settingsSaveReport("unsaved", "acct", "stored"),
        )
        assertEquals(
            "settings save save-anyway account=acct failed keystore",
            settingsSaveReport("save-anyway", "acct", settingsSaveFailed("keystore")),
        )
        assertEquals(SettingsSaveChoice.Store, settingsSaveChoice(confirmed = false, review = null))
    }

    @Test fun storedOnlyWhenTheAccountGainedTheEdits() {
        installGeneratedSettings()
        val before = newAccountSettings().copy(
            imapHost = "old.example",
            imapPort = 993,
            smtpHost = "smtp.example",
            smtpPort = 587,
            username = "ada",
            email = "ada@old.example",
            signature = "old",
        )
        var record = accountUserData("acct", before, null)

        val bad = before.copy(imapPort = 0)
        val reason = rejectedAccountSettings(bad)
        assertEquals("imapPort rejected 0", reason)
        val rejected = settingsSaveVerdict(
            rejected = reason,
            before = before,
            screen = bad,
            keysWritten = emptySet(),
            wrotePassword = false,
            wroteSmtp = false,
            wroteSieve = false,
            needPassword = false,
            needSmtp = false,
            needSieve = false,
            record = record,
            checkRecord = true,
        )
        assertFalse(rejected.stored)
        assertEquals(reason, rejected.failure)
        assertEquals("old.example", overlayAccountFields(before, record).imapHost)
        assertEquals(
            "settings save save-anyway account=acct failed imapPort rejected 0",
            settingsSaveReport("save-anyway", "acct", rejected.outcome),
        )

        val screen = before.copy(imapHost = "new.example", signature = "new sig")
        assertFalse(sameServer(screen, before))
        val empty = settingsSaveVerdict(
            rejected = null,
            before = before,
            screen = screen,
            keysWritten = emptySet(),
            wrotePassword = false,
            wroteSmtp = false,
            wroteSieve = false,
            needPassword = false,
            needSmtp = false,
            needSieve = false,
            record = record,
            checkRecord = true,
        )
        assertFalse(empty.stored)
        assertEquals(settingsSaveEmptyWrite, empty.failure)
        assertEquals("old.example", overlayAccountFields(before, record).imapHost)
        assertEquals("old", overlayAccountFields(before, record).signature)
        assertEquals(
            "settings save unsaved account=acct failed empty write",
            settingsSaveReport("unsaved", "acct", empty.outcome),
        )

        val keys = screen.changedKeysAgainst(before)
        assertTrue(keys.contains("imapHost"))
        assertTrue(keys.contains("signature"))
        val stale = settingsSaveVerdict(
            rejected = null,
            before = before,
            screen = screen,
            keysWritten = keys,
            wrotePassword = false,
            wroteSmtp = false,
            wroteSieve = false,
            needPassword = false,
            needSmtp = false,
            needSieve = false,
            record = record,
            checkRecord = true,
        )
        assertFalse(stale.stored)
        assertEquals("old.example", overlayAccountFields(before, record).imapHost)

        record = accountUserData("acct", screen, null)
        val wrote = settingsSaveVerdict(
            rejected = null,
            before = before,
            screen = screen,
            keysWritten = keys,
            wrotePassword = false,
            wroteSmtp = false,
            wroteSieve = false,
            needPassword = false,
            needSmtp = false,
            needSieve = false,
            record = record,
            checkRecord = true,
        )
        assertTrue(wrote.stored)
        assertEquals("stored", wrote.outcome)
        val account = applyWrittenKeys(before, screen, keys)
        val loaded = overlayAccountFields(account, record)
        assertEquals(screen.imapHost, loaded.imapHost)
        assertEquals(screen.imapPort, loaded.imapPort)
        assertEquals(screen.smtpHost, loaded.smtpHost)
        assertEquals(screen.smtpPort, loaded.smtpPort)
        assertEquals(screen.username, loaded.username)
        assertEquals(screen.email, loaded.email)
        assertEquals(screen.signature, loaded.signature)
        assertEquals(
            "settings save account-save account=acct stored",
            settingsSaveReport("account-save", "acct", wrote.outcome),
        )
    }

    @Test fun checkInFlightStaysOnTheAccountItStarted() {
        val before = newAccountSettings().copy(
            imapHost = "old.example",
            imapPort = 993,
            smtpHost = "smtp.example",
            smtpPort = 587,
            username = "ada",
            email = "ada@old.example",
        )
        val screen = before.copy(imapHost = "started.example")
        val started = SaveCapture(
            accountId = "acct-a",
            before = before,
            screen = screen,
            password = "pw-a",
            smtp = "smtp-a",
            sieve = "sieve-a",
            creating = false,
        )
        val other = newAccountSettings().copy(
            imapHost = "other.example",
            imapPort = 143,
            smtpHost = "smtp.other",
            smtpPort = 25,
            username = "bea",
            email = "bea@other.example",
        )
        val bound = boundSaveCapture(started, "acct-b")
        assertEquals("acct-a", bound.accountId)
        assertEquals(screen, bound.screen)
        assertEquals(before, bound.before)
        assertEquals("pw-a", bound.password)
        assertEquals("smtp-a", bound.smtp)
        assertEquals("sieve-a", bound.sieve)
        assertEquals(
            "settings save account-save account=acct-a stored",
            settingsSaveReport("account-save", bound.accountId, "stored"),
        )
        val keys = bound.screen.changedKeysAgainst(bound.before)
        assertTrue(keys.contains("imapHost"))
        val ontoOther = settingsSaveVerdict(
            rejected = null,
            before = bound.before,
            screen = bound.screen,
            keysWritten = keys,
            wrotePassword = true,
            wroteSmtp = true,
            wroteSieve = true,
            needPassword = true,
            needSmtp = true,
            needSieve = true,
            record = accountUserData("acct-b", other, null),
            checkRecord = true,
        )
        assertFalse(ontoOther.stored)
        val ontoStarted = settingsSaveVerdict(
            rejected = null,
            before = bound.before,
            screen = bound.screen,
            keysWritten = keys,
            wrotePassword = true,
            wroteSmtp = true,
            wroteSieve = true,
            needPassword = true,
            needSmtp = true,
            needSieve = true,
            record = accountUserData("acct-a", bound.screen, null),
            checkRecord = true,
        )
        assertTrue(ontoStarted.stored)
        assertEquals("started.example", overlayAccountFields(applyWrittenKeys(bound.before, bound.screen, keys), accountUserData("acct-a", bound.screen, null)).imapHost)
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
