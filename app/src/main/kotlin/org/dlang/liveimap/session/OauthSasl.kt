package org.dlang.liveimap.session

import java.util.Base64

/** OAUTHBEARER initial response, base64. Fails before a payload when the username contains `,` or 0x01. */
fun oauthBearerInitial(user: String, host: String, port: Int, token: String): String {
    if (user.indexOf(',') >= 0 || user.indexOf('\u0001') >= 0) {
        throw IllegalArgumentException("username cannot be used with OAUTHBEARER")
    }
    val raw = buildString {
        append("n,a=")
        append(user)
        append(',')
        append('\u0001')
        append("host=")
        append(host)
        append('\u0001')
        append("port=")
        append(port)
        append('\u0001')
        append("auth=Bearer ")
        append(token)
        append('\u0001')
        append('\u0001')
    }
    return Base64.getEncoder().encodeToString(raw.toByteArray(Charsets.UTF_8))
}

fun xoauth2Initial(user: String, token: String): String {
    val raw = "user=$user\u0001auth=Bearer $token\u0001\u0001"
    return Base64.getEncoder().encodeToString(raw.toByteArray(Charsets.UTF_8))
}

fun oauthAdvertised(capabilities: String): List<String> {
    val offered = HashSet<String>()
    for (token in capabilities.split(Regex("\\s+"))) {
        val upper = token.uppercase()
        if (!upper.startsWith("AUTH=")) continue
        val name = upper.removePrefix("AUTH=")
        if (name == "OAUTHBEARER" || name == "XOAUTH2") offered.add(name)
    }
    val order = ArrayList<String>(2)
    if ("OAUTHBEARER" in offered) order += "OAUTHBEARER"
    if ("XOAUTH2" in offered) order += "XOAUTH2"
    return order
}

/** One mechanism. auto prefers OAUTHBEARER, then XOAUTH2. Empty when that mechanism is not advertised. */
fun chooseOauthMechanism(capabilities: String, preference: String): String? {
    val offered = oauthAdvertised(capabilities)
    val mode = preference.uppercase().removePrefix("OAUTH:")
    return when (mode) {
        "OAUTHBEARER" -> if ("OAUTHBEARER" in offered) "OAUTHBEARER" else null
        "XOAUTH2" -> if ("XOAUTH2" in offered) "XOAUTH2" else null
        else -> offered.firstOrNull()
    }
}

fun oauthUnavailableText(capabilities: String, preference: String): String {
    val offered = oauthAdvertised(capabilities)
    val mode = preference.uppercase().removePrefix("OAUTH:")
    val named = offered.joinToString(" ").ifEmpty { "none" }
    return if (mode == "OAUTHBEARER" || mode == "XOAUTH2") {
        "server does not advertise $mode (server offers: $named)"
    } else {
        "server does not advertise OAUTHBEARER or XOAUTH2 (server offers: $named)"
    }
}
