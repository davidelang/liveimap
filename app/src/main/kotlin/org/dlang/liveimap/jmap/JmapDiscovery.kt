package org.dlang.liveimap.jmap

import java.net.URI

sealed class JmapOffer {
    data class Mail(val session: JmapSession) : JmapOffer()
    data object None : JmapOffer()
}

data class JmapHttpResult(
    val status: Int,
    val body: String,
    val finalUrl: String,
)

fun interface JmapFetch {
    fun get(url: String): JmapHttpResult
}

fun jmapDiscoveryUrl(host: String, port: Int): String {
    val name = jmapHost(host)
    if (port !in 1..65535) throw JmapFailure("jmap port is invalid")
    val authority = if (port == 443) name else "$name:$port"
    return "https://$authority/.well-known/jmap"
}

fun discoverJmap(status: Int, body: String, finalUrl: String): JmapOffer {
    if (finalUrl.isBlank()) return JmapOffer.None
    val scheme = try {
        URI(finalUrl).scheme
    } catch (rejected: Exception) {
        return JmapOffer.None
    }
    if (scheme == null || !scheme.equals("https", ignoreCase = true)) return JmapOffer.None
    if (status != 200) return JmapOffer.None
    val session = try {
        parseJmapSession(body)
    } catch (unreadable: Exception) {
        return JmapOffer.None
    }
    if (!session.offersMail()) return JmapOffer.None
    return JmapOffer.Mail(session)
}

fun discoverAt(host: String, port: Int, fetch: JmapFetch): JmapOffer {
    val result = fetch.get(jmapDiscoveryUrl(host, port))
    return discoverJmap(result.status, result.body, result.finalUrl)
}

private fun jmapHost(host: String): String {
    if (host.isBlank()) throw JmapFailure("jmap host is empty")
    if (host.contains("://") ||
        host.any { it == '/' || it == '\\' || it == '@' || it.isWhitespace() }
    ) {
        throw JmapFailure("jmap host is not a name")
    }
    if (host.contains(':')) return ipv6Host(host)
    return host
}

private fun ipv6Host(host: String): String {
    val inner = if (host.startsWith('[') && host.endsWith(']')) {
        host.substring(1, host.length - 1)
    } else if (host.contains('[') || host.contains(']')) {
        throw JmapFailure("jmap host is not a name")
    } else {
        host
    }
    if (inner.isEmpty() || inner.any { !ipv6Char(it) }) {
        throw JmapFailure("jmap host is not a name")
    }
    return "[$inner]"
}

private fun ipv6Char(c: Char): Boolean =
    c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F' || c == ':'
