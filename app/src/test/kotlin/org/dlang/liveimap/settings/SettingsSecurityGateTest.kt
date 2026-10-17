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
}
