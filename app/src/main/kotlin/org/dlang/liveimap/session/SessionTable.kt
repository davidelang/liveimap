package org.dlang.liveimap.session

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference

class SessionTable(
    private val factory: () -> MailSession,
) {
    private val sessions = ConcurrentHashMap<String, MailSession>()
    private val confirmers = AtomicReference(ConfirmerPair(null, null))
    private val opening = ConcurrentHashMap<String, CountDownLatch>()

    fun session(accountId: String): MailSession {
        if (accountId.isEmpty()) throw IllegalArgumentException("account id")
        while (true) {
            val pending = opening[accountId]
            if (pending != null) {
                pending.await()
                continue
            }
            val existing = sessions[accountId]
            if (existing != null) {
                val again = opening[accountId]
                if (again != null) {
                    again.await()
                    continue
                }
                return existing
            }
            val gate = CountDownLatch(1)
            if (opening.putIfAbsent(accountId, gate) != null) continue
            try {
                var created: MailSession? = null
                val stored = sessions.compute(accountId) { _, present ->
                    if (present != null) return@compute present
                    val made = factory()
                    val pair = confirmers.get()
                    made.setCertConfirmer(pair.cert)
                    made.setPlaintextConfirmer(pair.plain)
                    created = made
                    made
                }!!
                if (created != null) settleConfirmers(stored)
                return stored
            } finally {
                gate.countDown()
                opening.remove(accountId, gate)
            }
        }
    }

    // The pair read inside compute can be older than a confirmer publish that already walked the
    // map. Re-apply after the session is visible, until that pair is still current.
    private fun settleConfirmers(session: MailSession) {
        while (true) {
            val pair = confirmers.get()
            session.setCertConfirmer(pair.cert)
            session.setPlaintextConfirmer(pair.plain)
            if (confirmers.get() === pair) return
        }
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
