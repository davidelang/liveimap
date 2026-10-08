package org.dlang.liveimap.session

private val plusImapAuth = listOf("SCRAM-SHA-256-PLUS", "SCRAM-SHA-1-PLUS")

private val preferredImapAuth = listOf("SCRAM-SHA-256", "SCRAM-SHA-1", "CRAM-MD5")

private val plaintextImapAuth = listOf("PLAIN", "LOGIN")

/**
 * Picks one SASL mechanism from an IMAP capability line.
 * A null result is not the IMAP LOGIN command.
 */
fun chooseImapAuth(
    capabilities: String,
    tls: Boolean,
    plaintextOk: Boolean,
    channelBinding: Boolean,
): String? {
    val allowPlus = tls && channelBinding
    val offered = HashSet<String>()
    for (token in capabilities.split(Regex("\\s+"))) {
        if (token.isEmpty()) continue
        val upper = token.uppercase()
        if (!upper.startsWith("AUTH=")) continue
        val name = upper.substring("AUTH=".length)
        if (name.endsWith("-PLUS")) {
            if (!allowPlus || name !in plusImapAuth) continue
        }
        offered.add(name)
    }
    if (allowPlus) {
        for (name in plusImapAuth) {
            if (name in offered) return name
        }
    }
    for (name in preferredImapAuth) {
        if (name in offered) return name
    }
    if (!tls && !plaintextOk) return null
    for (name in plaintextImapAuth) {
        if (name in offered) return name
    }
    return null
}
