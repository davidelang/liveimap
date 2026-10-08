package org.dlang.liveimap.session

class SessionTable(
    private val factory: () -> MailSession,
) {
    private val lock = Any()
    private val sessions = LinkedHashMap<String, MailSession>()
    private var certConfirmer: (suspend (CertPrompt) -> Boolean)? = null
    private var plaintextConfirmer: (suspend () -> Boolean)? = null

    fun session(accountId: String): MailSession {
        if (accountId.isEmpty()) throw IllegalArgumentException("account id")
        synchronized(lock) {
            val existing = sessions[accountId]
            if (existing != null) return existing
            val created = factory()
            created.setCertConfirmer(certConfirmer)
            created.setPlaintextConfirmer(plaintextConfirmer)
            sessions[accountId] = created
            return created
        }
    }

    fun setCertConfirmer(confirm: (suspend (CertPrompt) -> Boolean)?) {
        synchronized(lock) {
            certConfirmer = confirm
            for (session in sessions.values) {
                session.setCertConfirmer(confirm)
            }
        }
    }

    fun setPlaintextConfirmer(confirm: (suspend () -> Boolean)?) {
        synchronized(lock) {
            plaintextConfirmer = confirm
            for (session in sessions.values) {
                session.setPlaintextConfirmer(confirm)
            }
        }
    }

    suspend fun suspendConnections() {
        val copy = synchronized(lock) { sessions.values.toList() }
        for (session in copy) {
            session.suspendConnections()
        }
    }

    suspend fun drop(accountId: String) {
        val removed = synchronized(lock) { sessions.remove(accountId) } ?: return
        removed.suspendConnections()
    }
}
