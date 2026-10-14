package org.dlang.liveimap.session

import kotlinx.coroutines.runBlocking
import org.dlang.liveimap.accounts.AccountCredentials
import org.dlang.liveimap.settings.AccountSettings
import org.junit.Assert.assertEquals
import org.junit.Test

private class SuspendRec(val name: String, val log: MutableList<String>) :
    MailSession by DisconnectedMailSession() {
    override suspend fun open(account: AccountSettings): OpenResult {
        log += "$name open"
        return OpenResult.Connected
    }

    override suspend fun suspendConnections() {
        log += "$name suspend"
    }

    override fun close() {
        log += "$name close"
    }
}

class RoutedSuspendTest {
    @Test
    fun suspendAndCloseHitBothSessions() = runBlocking {
        val log = mutableListOf<String>()
        val routed = RoutedMailSession({ SuspendRec("imap", log) }, { SuspendRec("jmap", log) })
        routed.open(AccountSettings(username = "u", jmapFolders = true))
        routed.openWith(AccountCredentials("a", AccountSettings(username = "u"), "pw", ""))
        log.clear()
        routed.suspendConnections()
        assertEquals(listOf("imap suspend", "jmap suspend"), log)
        log.clear()
        routed.close()
        assertEquals(listOf("imap close", "jmap close"), log)
    }
}
