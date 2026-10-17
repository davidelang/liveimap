package org.dlang.liveimap.settings

import kotlinx.coroutines.runBlocking
import org.dlang.liveimap.session.SmtpLoginCheck
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class SettingsSecurityGateTest {
    @Test
    fun testAccountStopsWhenASecurityLineIsPreserved() = runBlocking {
        val settings = AccountSettings(unknownSettings = listOf("tlsMode=NotAMode"))
        val probes = AccountProbes(
            imapLogin = { fail("imap") },
            smtpLogin = { fail("smtp"); SmtpLoginCheck.LoggedIn },
            jmapLogin = { fail("jmap") },
        )
        val lines = testAccountLogins(settings, probes)
        assertEquals(listOf("FAIL account settings need attention: tlsMode"), lines)
    }

    @Test
    fun withoutPreservedDropsTlsModeAndLeavesOtherSettings() {
        val broken = AccountSettings(unknownSettings = listOf("tlsMode=NotAMode"))
        val cleared = broken.withoutPreserved("tlsMode")
        assertEquals(emptyList<String>(), cleared.unknownSettings)
        assertEquals(null, cleared.preservedSecurityKey())
        val plain = AccountSettings(username = "ada", imapHost = "imap.example")
        assertEquals(plain, plain.withoutPreserved("tlsMode"))
    }
}
