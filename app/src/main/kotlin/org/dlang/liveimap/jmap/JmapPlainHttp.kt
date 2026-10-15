package org.dlang.liveimap.jmap

import java.net.URI
import java.util.concurrent.ConcurrentHashMap

/**
 * David (2026-10-13): the app does not refuse what the user asks for. When the JMAP server field is an http://
 * URL, that one origin (scheme, host, port) may be used without TLS; Settings shows a warning. Every other URL
 * still has to be https, so a server cannot move an https account onto plain http (redirects, session URLs).
 */
object JmapPlainHttp {
    private val origins = ConcurrentHashMap<String, MutableSet<String>>()

    fun allow(accountKey: String, url: String) {
        if (accountKey.isBlank()) return
        val origin = originOf(url) ?: return
        origins.computeIfAbsent(accountKey) { ConcurrentHashMap.newKeySet() }.add(origin)
    }

    fun allowed(accountKey: String, url: String): Boolean {
        if (accountKey.isBlank()) return false
        val origin = originOf(url) ?: return false
        return origins[accountKey]?.contains(origin) == true
    }

    /** Drops this key's origins and no other key's. */
    fun drop(accountKey: String) {
        origins.remove(accountKey)
    }

    internal fun clear() = origins.clear()

    /** "http://host:port" (port 80 when absent), lowercase; null unless the URL is http. */
    fun originOf(url: String): String? {
        val m = Regex("^http://(\\[[^\\]]+\\]|[^/:?#{}]+)(?::(\\d{1,5}))?(?:[/?#{]|$)", RegexOption.IGNORE_CASE).find(url.trim())
            ?: return null
        val port = m.groupValues[2].ifEmpty { "80" }.toInt()
        return "http://" + m.groupValues[1].lowercase() + ":" + port
    }
}

/** https always; http only for an origin this account configured. A blank key allows https and refuses http. */
fun jmapUrlAllowed(accountKey: String, url: String): Boolean {
    val scheme = try { URI(url.substringBefore('{')).scheme } catch (_: Exception) { null }
        ?: Regex("^([a-zA-Z][a-zA-Z0-9+.-]*)://").find(url.trim())?.groupValues?.get(1)
    if (scheme.equals("https", ignoreCase = true)) return true
    return scheme.equals("http", ignoreCase = true) && JmapPlainHttp.allowed(accountKey, url)
}

/** The certificate verdict, skipped only for this key's plain-http origin (there is no certificate). */
inline fun jmapVerdict(accountKey: String, url: String, check: () -> String): String =
    if (JmapPlainHttp.allowed(accountKey, url)) "" else check()
