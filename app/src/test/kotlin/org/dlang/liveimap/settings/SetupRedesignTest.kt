package org.dlang.liveimap.settings

import java.io.File
import kotlinx.coroutines.runBlocking
import org.dlang.liveimap.R
import org.dlang.liveimap.session.SmtpLoginCheck
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** David, 2026-10-12: setup page order, JMAP sending, flowed text, no Advanced, Test account next to Save. */
class SetupRedesignTest {
    private val screen = listOf("src/main/kotlin/org/dlang/liveimap/settings/SettingsScreen.kt", "app/src/main/kotlin/org/dlang/liveimap/settings/SettingsScreen.kt")
        .map(::File).first { it.exists() }.readText()

    private fun group(name: String): String {
        val start = screen.indexOf("private fun $name(")
        val next = screen.indexOf("\nprivate fun ", start + 1).let { if (it < 0) screen.length else it }
        return screen.substring(start, next)
    }

    @Test fun identityThenAccessThenServerThenSending() {
        val g = group("AccountGroup")
        val identity = g.indexOf("R.string.settings_identity")
        val access = g.indexOf("title = stringResource(R.string.settings_mail_access)")
        val server = g.indexOf("title = stringResource(R.string.settings_server)")
        val jmap = g.indexOf("title = stringResource(R.string.settings_jmap_server)")
        val sending = g.indexOf("title = stringResource(R.string.settings_sending)")
        assertTrue(identity in 0 until access)
        assertTrue(access < jmap && access < server)
        assertTrue(server < sending && jmap < sending)
    }

    @Test fun noAdvancedSectionAndTestNextToSave() {
        val g = group("AccountGroup")
        assertFalse(g.contains("R.string.settings_advanced"))
        val save = g.indexOf("testTag(\"account-save\")")
        val test = g.indexOf("testTag(\"account-test\")")
        assertTrue(save > 0 && test > save && test - save < 2500)
    }

    @Test fun flowedIsInCompose() {
        assertFalse(group("AccountGroup").contains("settings_flowed_text"))
        assertTrue(group("ComposeGroup").contains("settings_flowed_text"))
    }

    @Test fun smtpFieldsHiddenWhenSendingOverJmap() {
        val g = group("AccountGroup")
        val gate = g.indexOf("if (!settings.emailSubmission) {")
        assertTrue(gate > 0 && g.indexOf("R.string.settings_smtp_host") > gate)
    }

    @Test fun everyAccountCanSend() {
        val bare = newAccountSettings().copy(smtpHost = "", emailSubmission = false)
        assertEquals(R.string.settings_no_way_to_send, sendingProblem(bare))
        assertNull(sendingProblem(bare.copy(emailSubmission = true)))
        assertNull(sendingProblem(bare.copy(smtpHost = "smtp.x")))
        assertTrue(withMailAccess(bare, jmap = true).emailSubmission)
        assertFalse(withMailAccess(bare.copy(smtpHost = "smtp.x"), jmap = true).emailSubmission)
    }

    private fun probes(log: MutableList<String>, smtp: SmtpLoginCheck = SmtpLoginCheck.LoggedIn) = AccountProbes(
        imapLogin = { log += "imap" },
        smtpLogin = { log += "smtp"; smtp },
        jmapLogin = { log += "jmap" },
    )

    @Test fun testAccountLogsInToWhatTheAccountUses() = runBlocking {
        val log = ArrayList<String>()
        val imap = newAccountSettings().copy(smtpHost = "smtp.x")
        val lines = testAccountLogins(imap, probes(log))
        assertEquals(listOf("imap", "smtp"), log)
        assertTrue(lines.all { it.startsWith("ok") })
        log.clear()
        testAccountLogins(imap.copy(jmapFolders = true, emailSubmission = true), probes(log))
        assertEquals(listOf("jmap"), log)
    }

    @Test fun testAccountReportsFailuresAndMissingSending() = runBlocking {
        val failing = AccountProbes({ throw IllegalStateException("login failed: no") }, { SmtpLoginCheck.NoPassword }, {})
        val lines = testAccountLogins(newAccountSettings().copy(smtpHost = "smtp.x"), failing)
        assertTrue(lines[0].startsWith("FAIL IMAP login: login failed: no"))
        assertTrue(lines[1].startsWith("FAIL SMTP login"))
        val none = testAccountLogins(newAccountSettings().copy(smtpHost = ""), probes(ArrayList()))
        assertTrue(none.any { it.startsWith("FAIL sending") })
    }
}
