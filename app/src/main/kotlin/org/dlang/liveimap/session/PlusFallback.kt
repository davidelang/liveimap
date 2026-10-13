package org.dlang.liveimap.session

import org.dlang.liveimap.settings.AccountSettings
import org.dlang.liveimap.settings.TlsMode

private val plusFailure = Regex("SASL (SCRAM-SHA-[0-9]+)-PLUS failed")

/**
 * The account for a second login attempt after an automatically chosen SCRAM -PLUS mechanism failed:
 * the same mechanism without channel binding, same TLS mode. Null when no retry applies: a forced
 * mechanism (the user chose it), a plaintext connection, or any other failure.
 */
fun plusFallback(account: AccountSettings, failure: String): AccountSettings? {
    if (account.authMechanism.isNotBlank()) return null
    if (account.tlsMode == TlsMode.None) return null
    // F64: when the TLS peer was trusted only by the user (accept-invalid, or a pin the user confirmed),
    // channel binding is the remaining MITM protection. A failed -PLUS is then exactly what a relay looks like.
    if (account.acceptInvalidCert || account.certPin.isNotBlank()) return null
    val base = plusFailure.find(failure)?.groupValues?.get(1) ?: return null
    return account.copy(authMechanism = base)
}
