package org.dlang.liveimap.session

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.dlang.liveimap.engine.LibetpanMailSession
import org.dlang.liveimap.settings.AccountSettings

private val accountSessions = SessionTable {
    SerialMailSession(LibetpanMailSession())
}

private val extraIdleSessions = ExtraIdleSessions {
    SerialMailSession(LibetpanMailSession())
}

private val extraIdleLock = Mutex()

fun mailSession(accountId: String): MailSession = accountSessions.session(accountId)

fun setMailCertConfirmer(confirm: (suspend (CertPrompt) -> Boolean)?) {
    accountSessions.setCertConfirmer(confirm)
}

fun setMailPlaintextConfirmer(confirm: (suspend () -> Boolean)?) {
    accountSessions.setPlaintextConfirmer(confirm)
}

suspend fun suspendMailSessions() {
    accountSessions.suspendConnections()
}

suspend fun applyExtraIdle(account: AccountSettings) {
    extraIdleLock.withLock {
        applyStoredIdle(extraIdleSessions, account)
    }
}

suspend fun stopExtraIdle() {
    extraIdleLock.withLock {
        extraIdleSessions.stop()
    }
}

suspend fun dropMailSession(accountId: String) {
    accountSessions.drop(accountId)
}
