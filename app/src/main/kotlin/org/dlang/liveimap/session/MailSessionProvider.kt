package org.dlang.liveimap.session

import kotlinx.coroutines.flow.SharedFlow
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

suspend fun confirmMailCertificate(prompt: CertPrompt): Boolean {
    return accountSessions.confirmCertificate(prompt)
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

/** Extra IDLE changes from the process holder. Does not open a socket. */
fun extraIdleChanges(): SharedFlow<IdleFolderChange> = extraIdleSessions.changes

suspend fun dropMailSession(accountId: String) {
    accountSessions.drop(accountId)
}
