package org.dlang.liveimap.session

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.dlang.liveimap.accounts.AccountTarget
import org.dlang.liveimap.engine.LibetpanMailSession
import org.dlang.liveimap.settings.AccountSettings

// JMAP accounts (jmapFolders on) get a JmapMailSession at open; everything else, and all sending, stays libetpan.
private val accountSessions = SessionTable {
    SerialMailSession(RoutedMailSession({ LibetpanMailSession() }, { org.dlang.liveimap.jmap.platformJmapMailSession() }))
}

// Watched folders follow the account's kind too: a JMAP account watches them over its event source.
private val extraIdleSessions = ExtraIdleSessions {
    SerialMailSession(RoutedMailSession({ LibetpanMailSession() }, { org.dlang.liveimap.jmap.platformJmapMailSession() }))
}

private val extraIdleLock = Mutex()

fun mailSession(accountId: String): MailSession = accountSessions.session(accountId)

fun setMailCertConfirmer(confirm: (suspend (CertPrompt) -> Boolean)?) {
    accountSessions.setCertConfirmer(confirm)
}

suspend fun confirmMailCertificate(prompt: CertPrompt): Boolean {
    return accountSessions.confirmCertificate(prompt)
}

suspend fun confirmMailPlaintext(): Boolean = accountSessions.confirmPlaintext()

fun setMailPlaintextConfirmer(confirm: (suspend () -> Boolean)?) {
    accountSessions.setPlaintextConfirmer(confirm)
}

suspend fun suspendMailSessions() {
    accountSessions.suspendConnections()
}

suspend fun applyExtraIdle(account: AccountSettings, accountId: String) {
    if (accountId.isBlank()) return
    val seen = extraIdleSessions.readGeneration()
    extraIdleLock.withLock {
        if (!extraIdleSessions.generationStill(seen)) return
        withContext(AccountTarget(accountId)) {
            applyStoredIdle(extraIdleSessions, account, accountId, seen)
        }
    }
}

suspend fun stopExtraIdle() {
    extraIdleSessions.bumpGeneration()
    extraIdleLock.withLock {
        extraIdleSessions.stop()
    }
}

/** Extra IDLE changes from the process holder. Does not open a socket. */
fun extraIdleChanges(): kotlinx.coroutines.flow.Flow<IdleFolderChange> = extraIdleSessions.changes

suspend fun dropMailSession(accountId: String) {
    extraIdleLock.withLock {
        extraIdleSessions.closeAccount(accountId)
    }
    accountSessions.drop(accountId)
}
