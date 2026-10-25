package org.dlang.liveimap.session

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

class SessionTable(
    private val factory: () -> MailSession,
) {
    private val sessions = ConcurrentHashMap<String, MailSession>()
    private val confirmers = AtomicReference(ConfirmerPair(null, null))

    fun session(accountId: String): MailSession {
        if (accountId.isEmpty()) throw IllegalArgumentException("account id")
        return sessions.compute(accountId) { _, existing ->
            if (existing != null) return@compute existing
            val created = factory()
            val pair = confirmers.get()
            created.setCertConfirmer(pair.cert)
            created.setPlaintextConfirmer(pair.plain)
            created
        }!!
    }

    /** The session already open for [accountId], or null. Does not call the factory. */
    fun existing(accountId: String): MailSession? {
        if (accountId.isEmpty()) return null
        return sessions[accountId]
    }

    fun setCertConfirmer(confirm: (suspend (CertPrompt) -> Boolean)?) {
        val pair = confirmers.updateAndGet { it.copy(cert = confirm) }
        for (session in sessions.values) {
            session.setCertConfirmer(pair.cert)
        }
    }

    suspend fun confirmCertificate(prompt: CertPrompt): Boolean {
        val confirm = confirmers.get().cert ?: return false
        return confirm(prompt)
    }

    suspend fun confirmPlaintext(): Boolean {
        val confirm = confirmers.get().plain ?: return false
        return confirm()
    }

    fun setPlaintextConfirmer(confirm: (suspend () -> Boolean)?) {
        val pair = confirmers.updateAndGet { it.copy(plain = confirm) }
        for (session in sessions.values) {
            session.setPlaintextConfirmer(pair.plain)
        }
    }

    suspend fun suspendConnections() {
        val copy = sessions.values.toList()
        for (session in copy) {
            session.suspendConnections()
        }
    }

    suspend fun drop(accountId: String) {
        val removed = sessions.remove(accountId) ?: return
        removed.close()
    }
}

private data class ConfirmerPair(
    val cert: (suspend (CertPrompt) -> Boolean)?,
    val plain: (suspend () -> Boolean)?,
)
