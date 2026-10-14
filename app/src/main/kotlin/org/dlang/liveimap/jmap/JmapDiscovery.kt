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

/** Where the JMAP server field points: a host, its port (default 443), and the URL discovery fetches. */
data class JmapServerTarget(val host: String, val port: Int, val discoveryUrl: String, val plainHttp: Boolean = false)

/**
 * Reads the JMAP server field. Accepted: "host", "host:port", "[v6]" / "[v6]:port" / a bare IPv6 address, or a
 * full https URL ("https://host[:port]" discovers at /.well-known/jmap; with a path, that URL is the session
 * resource). Port defaults to 443. http, user info, queries and fragments are refused (credentials follow).
 */
fun jmapServerTarget(field: String): JmapServerTarget {
    val text = field.trim()
    if (text.isEmpty()) throw JmapFailure("jmap host is empty")
    if (text.contains("://")) {
        val uri = try { URI(text) } catch (_: Exception) { throw JmapFailure("jmap server url is not valid") }
        // http:// only when the user typed it (David: never refuse the user); Settings warns.
        val plain = uri.scheme.equals("http", ignoreCase = true)
        if (!plain && !uri.scheme.equals("https", ignoreCase = true)) throw JmapFailure("jmap server url is not http or https")
        val scheme = if (plain) "http" else "https"
        val defaultPort = if (plain) 80 else 443
        if (uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null) {
            throw JmapFailure("jmap server url has extra parts")
        }
        val rawHost = uri.host ?: throw JmapFailure("jmap server url has no host")
        val host = jmapHost(rawHost.removePrefix("[").removeSuffix("]").let { if (it.contains(':')) "[$it]" else it })
        val port = if (uri.port == -1) defaultPort else uri.port
        if (port !in 1..65535) throw JmapFailure("jmap port is invalid")
        val path = uri.rawPath.orEmpty()
        val authority = if (port == defaultPort) host else "$host:$port"
        val url = if (path.isEmpty() || path == "/") "$scheme://$authority/.well-known/jmap" else "$scheme://$authority$path"
        return JmapServerTarget(host, port, url, plain)
    }
    // "[v6]:port" / "[v6]"
    if (text.startsWith('[')) {
        val close = text.indexOf(']')
        if (close < 0) throw JmapFailure("jmap host is not a name")
        val host = jmapHost(text.substring(0, close + 1))
        val rest = text.substring(close + 1)
        val port = when {
            rest.isEmpty() -> 443
            rest.startsWith(':') -> parsePort(rest.substring(1))
            else -> throw JmapFailure("jmap host is not a name")
        }
        return JmapServerTarget(host, port, jmapDiscoveryUrl(host, port))
    }
    val colons = text.count { it == ':' }
    if (colons == 1) {
        val host = jmapHost(text.substringBefore(':'))
        val port = parsePort(text.substringAfter(':'))
        return JmapServerTarget(host, port, jmapDiscoveryUrl(host, port))
    }
    val host = jmapHost(text) // a name, or a bare IPv6 address (several colons, no port)
    return JmapServerTarget(host, 443, jmapDiscoveryUrl(host, 443))
}

private fun parsePort(text: String): Int {
    if (text.isEmpty() || text.length > 5 || !text.all { it in '0'..'9' }) throw JmapFailure("jmap port is invalid")
    val port = text.toInt()
    if (port !in 1..65535) throw JmapFailure("jmap port is invalid")
    return port
}

fun discoverJmap(status: Int, body: String, finalUrl: String): JmapOffer {
    if (finalUrl.isBlank()) return JmapOffer.None
    val scheme = try {
        URI(finalUrl).scheme
    } catch (rejected: Exception) {
        return JmapOffer.None
    }
    if (scheme == null || !jmapUrlAllowed(finalUrl)) return JmapOffer.None
    if (status != 200) return JmapOffer.None
    var session = try {
        parseJmapSession(body)
    } catch (unreadable: Exception) {
        return JmapOffer.None
    }
    if (!session.offersMail()) return JmapOffer.None
    // Cyrus sends path-only session URLs ("/jmap/"); they are relative to the session resource.
    val origin = Regex("^(https?://[^/?#]+)", RegexOption.IGNORE_CASE).find(finalUrl)?.groupValues?.get(1) ?: return JmapOffer.None
    fun abs(u: String) = if (u.startsWith("/") && !u.startsWith("//")) origin + u else u
    session = session.copy(apiUrl = abs(session.apiUrl), downloadUrl = abs(session.downloadUrl),
        uploadUrl = abs(session.uploadUrl), eventSourceUrl = abs(session.eventSourceUrl))
    // F53/F79: credentials go to the session's URLs, so they must stay on the host that was discovered.
    if (!jmapSameOrigin(finalUrl, session)) return JmapOffer.None
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
    if (host.contains('[') || host.contains(']')) {
        throw JmapFailure("jmap host is not a name")
    }
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

/** True when every URL in [session] is on the host [finalUrl] was served from. */
fun jmapSameOrigin(finalUrl: String, session: JmapSession): Boolean {
    val origin = jmapUrlHost(finalUrl) ?: return false
    return listOf(session.apiUrl, session.uploadUrl, session.downloadUrl, session.eventSourceUrl)
        .all { it.isBlank() || jmapUrlHost(it) == origin }
}
