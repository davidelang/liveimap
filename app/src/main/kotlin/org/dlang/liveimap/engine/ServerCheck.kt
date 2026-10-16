package org.dlang.liveimap.engine

import org.dlang.liveimap.session.imapAuthOrder
import org.dlang.liveimap.settings.AccountSettings
import org.dlang.liveimap.settings.TlsMode

/** What the settings check saw: native probe lines (error=, pre=, post=, starttls=absent, tls=, trust=, accepted=). */
data class ServerProbe(
    val error: String = "",
    val pre: String = "",
    val post: String = "",
    val startTlsAbsent: Boolean = false,
    val tls: String = "",
    val trust: String = "",
    val accepted: String = "",
) {
    companion object {
        fun parse(lines: Array<String>?): ServerProbe {
            if (lines == null) return ServerProbe(error = "probe failed")
            var probe = ServerProbe()
            for (line in lines) {
                val key = line.substringBefore('=')
                val value = line.substringAfter('=', "")
                probe = when (key) {
                    "error" -> probe.copy(error = value)
                    "pre" -> probe.copy(pre = value)
                    "post" -> probe.copy(post = value)
                    "starttls" -> probe.copy(startTlsAbsent = value == "absent")
                    "tls" -> probe.copy(tls = value)
                    "trust" -> probe.copy(trust = value)
                    "accepted" -> probe.copy(accepted = value)
                    else -> probe
                }
            }
            return probe
        }
    }
}

/** The result shown when a server is saved: conflicts block (with Save anyway); notes are informational. */
data class ServerCheck(
    val reachable: Boolean,
    val conflicts: List<String>,
    val notes: List<String> = emptyList(),
)

/**
 * Judges a probe against the account's settings. oldTlsWorks is the result of a second probe with
 * allowOldTls on, made only when the first TLS attempt failed with the setting off (null when not tried).
 */
fun checkServer(settings: AccountSettings, probe: ServerProbe, oldTlsWorks: Boolean? = null): ServerCheck {
    if (probe.error.isNotEmpty()) {
        return ServerCheck(reachable = false, conflicts = listOf("server not reachable: ${probe.error}"))
    }
    val conflicts = ArrayList<String>()
    val notes = ArrayList<String>()
    if (probe.startTlsAbsent) {
        conflicts.add("server does not offer STARTTLS (use implicit TLS, usually port 993)")
    }
    if (probe.tls.isNotEmpty()) {
        if (oldTlsWorks == true && !settings.allowOldTls) {
            conflicts.add("server needs Allow old TLS and weak keys (${probe.tls})")
        } else {
            conflicts.add("TLS failed: ${probe.tls}")
        }
    }
    if (probe.trust.isNotEmpty()) {
        conflicts.add("certificate fails validation: ${probe.trust}")
    }
    if (probe.accepted.isNotEmpty()) {
        notes.add("certificate fails validation (${probe.accepted}); accepted by the Accept invalid certificate setting")
    }
    if (settings.allowOldTls && probe.tls.isEmpty() && settings.tlsMode != TlsMode.None) {
        notes.add("Allow old TLS is on for this account")
    }
    val caps = probe.post.ifEmpty { probe.pre }
    if (caps.isEmpty()) return ServerCheck(reachable = true, conflicts = conflicts, notes = notes)
    val words = caps.split(' ').filter { it.isNotEmpty() }.map { it.uppercase() }.toSet()
    if ("IMAP4REV1" !in words && "IMAP4REV2" !in words) conflicts.add("server does not advertise IMAP4rev1")
    val mechanisms = words.filter { it.startsWith("AUTH=") }.map { it.removePrefix("AUTH=") }.toSet()
    val loginDisabled = "LOGINDISABLED" in words
    val secure = settings.tlsMode != TlsMode.None
    val hasClientCert = settings.clientCert.isNotBlank() || settings.clientCertAlias.isNotBlank()
    val couldUse = imapAuthOrder(caps, tls = secure, plaintextOk = true, channelBinding = secure, clientMechanisms = "", clientCert = hasClientCert)
    val withoutPlaintext = imapAuthOrder(caps, tls = secure, plaintextOk = false, channelBinding = secure, clientMechanisms = "", clientCert = hasClientCert)
    if (loginDisabled && couldUse.isEmpty()) {
        conflicts.add(
            if (!secure) "plaintext login disabled by the server; use STARTTLS or implicit TLS"
            else "no supported login method (server offers: ${mechanisms.sorted().joinToString(" ").ifEmpty { "none" }})",
        )
    } else if (!secure && !settings.allowPlaintextAuth && withoutPlaintext.isEmpty()) {
        conflicts.add("plaintext connection: login needs Allow plaintext authentication (or use TLS)")
    }
    if (mechanisms.isNotEmpty() && couldUse.isEmpty() && !loginDisabled) {
        notes.add("server offers no SASL mechanism the app supports (${mechanisms.sorted().joinToString(" ")}); LOGIN is used")
    }
    return ServerCheck(reachable = true, conflicts = conflicts, notes = notes)
}
