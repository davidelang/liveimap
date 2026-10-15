package org.dlang.liveimap.engine

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * Per-account TLS relaxations from Advanced settings, both off by default.
 * acceptInvalidCert accepts a certificate that fails validation (name, expiry, trust), like pine
 * /novalidate-cert; connection and handshake failures are never overridden. allowOldTls allows TLS 1.0/1.1,
 * small DH keys and legacy ciphers for that account's connections only. Also a coroutine context element, so
 * ManageSieve picks it up without threading it through every call.
 */
class TlsPolicy(
    val acceptInvalidCert: Boolean = false,
    val allowOldTls: Boolean = false,
) : AbstractCoroutineContextElement(TlsPolicy) {
    companion object Key : CoroutineContext.Key<TlsPolicy>

    override fun equals(other: Any?): Boolean =
        other is TlsPolicy && other.acceptInvalidCert == acceptInvalidCert && other.allowOldTls == allowOldTls

    override fun hashCode(): Int = (if (acceptInvalidCert) 1 else 0) + (if (allowOldTls) 2 else 0)
}

/** Certificate verdicts from PeerTrust that acceptInvalidCert overrides. */
val certificateVerdicts: Set<String> = setOf(
    "name mismatch",
    "certificate expired",
    "certificate untrusted",
    "certificate rejected",
)

/** The failure after the policy: empty when the policy accepts this certificate verdict. */
fun applyTlsPolicy(failure: String, policy: TlsPolicy): String =
    if (failure.isNotEmpty() && policy.acceptInvalidCert && failure in certificateVerdicts) "" else failure
