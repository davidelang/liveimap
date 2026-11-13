package org.dlang.liveimap.session

private val plusImapAuth = listOf("SCRAM-SHA-256-PLUS", "SCRAM-SHA-1-PLUS")

private val scramImapAuth = listOf("SCRAM-SHA-256", "SCRAM-SHA-1")

private val plaintextImapAuth = listOf("PLAIN", "LOGIN")

private val imapTokenSplit = Regex("\\s+")

/**
 * Picks one SASL mechanism from an IMAP capability line (the first of [imapAuthOrder]).
 * A null result is not the IMAP LOGIN command.
 */
fun chooseImapAuth(
    capabilities: String,
    tls: Boolean,
    plaintextOk: Boolean,
    channelBinding: Boolean,
    clientMechanisms: String = "",
    clientCert: Boolean = false,
): String? = imapAuthOrder(capabilities, tls, plaintextOk, channelBinding, clientMechanisms, clientCert).firstOrNull()

/** Password SMTP uses [chooseImapAuth]. An OAuth mode picks one bearer mechanism and nothing else. */
fun chooseSmtpAuthMechanism(
    capabilities: String,
    tls: Boolean,
    plaintextOk: Boolean,
    channelBinding: Boolean,
    clientCert: Boolean,
    oauthMode: String = "",
): String? {
    if (oauthMode.isNotEmpty()) {
        if (!tls) return null
        return chooseOauthMechanism(capabilities, oauthMode)
    }
    return chooseImapAuth(capabilities, tls, plaintextOk, channelBinding, "", clientCert)
}

/** For the native login: [imapAuthOrder] as one space-separated line. C calls [ImapAuthNative]. */
fun imapAuthOrderLine(
    capabilities: String,
    tls: Boolean,
    plaintextOk: Boolean,
    channelBinding: Boolean,
    clientMechanisms: String,
    clientCert: Boolean,
): String = imapAuthOrder(capabilities, tls, plaintextOk, channelBinding, clientMechanisms, clientCert).joinToString(" ")

/**
 * Every usable SASL mechanism, best first (David, 2026-10-12). The login tries the next one only when a mechanism
 * fails locally (nothing was sent); a server rejection stops there.
 * - EXTERNAL with a client certificate on TLS; SCRAM-*-PLUS with channel binding; SCRAM-SHA-256, SCRAM-SHA-1.
 * - On TLS: PLAIN, LOGIN, then CRAM-MD5 (servers that check passwords through PAM/saslauthd advertise CRAM-MD5
 *   but can never verify it; inside TLS PLAIN is as safe and always works).
 * - Without TLS: CRAM-MD5 (the password is not sent), then PLAIN, LOGIN only when plaintext is allowed.
 */
fun imapAuthOrder(
    capabilities: String,
    tls: Boolean,
    plaintextOk: Boolean,
    channelBinding: Boolean,
    clientMechanisms: String = "",
    clientCert: Boolean = false,
    oauthMode: String = "",
): List<String> {
    if (oauthMode.isNotEmpty()) {
        val one = chooseOauthMechanism(capabilities, oauthMode)
        return if (one == null) emptyList() else listOf(one)
    }
    // Only mechanisms the local SASL library has; blank means unknown (no filter). X-PLUS comes with X.
    val client = clientMechanisms.uppercase().split(imapTokenSplit).filter { it.isNotEmpty() }.toSet()
    fun clientHas(name: String) = client.isEmpty() || name in client || name.removeSuffix("-PLUS") in client
    val allowPlus = tls && channelBinding
    val offered = HashSet<String>()
    for (token in capabilities.split(imapTokenSplit)) {
        if (token.isEmpty()) continue
        val upper = token.uppercase()
        if (!upper.startsWith("AUTH=")) continue
        val name = upper.substring("AUTH=".length)
        if (name.endsWith("-PLUS")) {
            if (!allowPlus || name !in plusImapAuth) continue
        }
        if (!clientHas(name)) continue
        offered.add(name)
    }
    val order = ArrayList<String>()
    if (tls && clientCert && "EXTERNAL" in offered) order += "EXTERNAL"
    if (allowPlus) order += plusImapAuth.filter { it in offered }
    order += scramImapAuth.filter { it in offered }
    if (tls) {
        order += plaintextImapAuth.filter { it in offered }
        if ("CRAM-MD5" in offered) order += "CRAM-MD5"
    } else {
        if ("CRAM-MD5" in offered) order += "CRAM-MD5"
        if (plaintextOk) order += plaintextImapAuth.filter { it in offered }
    }
    return order
}

/** Native login and SMTP AUTH call these. The names and the string arguments stay. */
@androidx.annotation.Keep
object ImapAuthNative {
    @JvmStatic
    fun imapAuthOrderLine(
        capabilities: String,
        tls: Boolean,
        plaintextOk: Boolean,
        channelBinding: Boolean,
        clientMechanisms: String,
        clientCert: Boolean,
    ): String = org.dlang.liveimap.session.imapAuthOrderLine(
        capabilities, tls, plaintextOk, channelBinding, clientMechanisms, clientCert,
    )

    @JvmStatic
    fun chooseImapAuth(
        capabilities: String,
        tls: Boolean,
        plaintextOk: Boolean,
        channelBinding: Boolean,
        clientMechanisms: String,
        clientCert: Boolean,
    ): String? = org.dlang.liveimap.session.chooseImapAuth(
        capabilities, tls, plaintextOk, channelBinding, clientMechanisms, clientCert,
    )
}
