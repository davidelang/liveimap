package org.dlang.liveimap.jmap

import org.dlang.liveimap.engine.PeerTrust
import java.net.URI
import javax.net.ssl.HttpsURLConnection

data class JmapPushEvent(
    val name: String,
    val data: String,
)

fun jmapEventSourceUrl(eventSourceUrl: String): String {
    if (eventSourceUrl.isBlank()) throw JmapFailure("jmap event source url is empty")
    if (eventSourceUrl.contains('#')) throw JmapFailure("jmap event source url has a fragment")
    // RFC 8620 §7.3: eventSourceUrl is an RFC 6570 level-1 template with {types}, {closeafter} and {ping}.
    if (eventSourceUrl.contains('{') || eventSourceUrl.contains('}')) return expandEventSourceTemplate(eventSourceUrl)
    if (!eventSourceIsHttps(eventSourceUrl)) {
        throw JmapFailure("jmap event source url is not https")
    }
    val joiner = if (eventSourceUrl.contains('?')) "&" else "?"
    return eventSourceUrl + joiner + "types=Email&closeafter=state&ping=120"
}

private val eventSourceValues = mapOf("types" to "Email", "closeafter" to "state", "ping" to "120")

/**
 * Level-1 expansion (simple string, unreserved characters kept, everything else percent-encoded). Unknown
 * variables expand to "" as RFC 6570 says for undefined ones; any operator or malformed brace is refused, so a
 * template cannot smuggle in a different host or path.
 */
internal fun expandEventSourceTemplate(template: String): String {
    val out = StringBuilder()
    var i = 0
    while (i < template.length) {
        val c = template[i]
        if (c == '}') throw JmapFailure("jmap event source url template is malformed")
        if (c != '{') { out.append(c); i++; continue }
        val end = template.indexOf('}', i)
        if (end < 0) throw JmapFailure("jmap event source url template is malformed")
        val name = template.substring(i + 1, end)
        if (name.isEmpty() || !name.all { it.isLetterOrDigit() || it == '_' }) {
            throw JmapFailure("jmap event source url template is not level 1")
        }
        for (b in (eventSourceValues[name] ?: "").toByteArray(Charsets.UTF_8)) {
            val ch = b.toInt().toChar()
            if (ch.isLetterOrDigit() && b >= 0 || ch in "-._~") out.append(ch) else out.append("%%%02X".format(b.toInt() and 0xff))
        }
        i = end + 1
    }
    val url = out.toString()
    val host = try { java.net.URI(url).host } catch (_: Exception) { null }
    val templateHost = try { java.net.URI(template.substringBefore('{')).host } catch (_: Exception) { null }
    if (host == null || !eventSourceIsHttps(url)) throw JmapFailure("jmap event source url is not https")
    if (templateHost == null || !host.equals(templateHost, ignoreCase = true)) {
        throw JmapFailure("jmap event source url template moves the host")
    }
    return url
}

fun parseJmapPushEvents(body: String): List<JmapPushEvent> {
    if (body.isBlank()) throw JmapFailure("jmap push response is empty")
    val events = mutableListOf<JmapPushEvent>()
    var name: String? = null
    val data = mutableListOf<String>()
    fun finish() {
        if (name == null && data.isEmpty()) return
        events.add(JmapPushEvent(name ?: "message", data.joinToString("\n")))
        name = null
        data.clear()
    }
    var start = 0
    while (start <= body.length) {
        val next = body.indexOf('\n', start)
        val end = if (next < 0) body.length else next
        var line = body.substring(start, end)
        if (line.endsWith('\r')) line = line.dropLast(1)
        if (line.isEmpty()) {
            finish()
        } else if (!line.startsWith(':')) {
            val colon = line.indexOf(':')
            val field = if (colon < 0) line else line.substring(0, colon)
            var value = if (colon < 0) "" else line.substring(colon + 1)
            if (value.startsWith(' ')) value = value.substring(1)
            when (field) {
                "event" -> name = value
                "data" -> data.add(value)
            }
        }
        if (next < 0) break
        start = next + 1
    }
    finish()
    return events
}

fun jmapPushEmailState(data: String, accountId: String): String? {
    if (accountId.isBlank()) throw JmapFailure("jmap account id is empty")
    val root = try {
        JsonParser(data).parseDocument()
    } catch (_: JsonBroken) {
        throw JmapFailure("jmap push data is not an object")
    }
    if (root !is Json.Obj) throw JmapFailure("jmap push data is not an object")
    val changed = root.fields["changed"] as? Json.Obj ?: return null
    val account = changed.fields[accountId] as? Json.Obj ?: return null
    val email = account.fields["Email"] ?: return null
    if (email !is Json.Str) throw JmapFailure("jmap push email state is not text")
    return email.text
}

fun jmapReadPush(
    eventSourceUrl: String,
    pin: String,
    open: (String) -> JmapHttpExchange,
    trust: (String, List<ByteArray>, String) -> String = PeerTrust::check,
): List<JmapPushEvent> {
    val url = jmapEventSourceUrl(eventSourceUrl)
    val result = JmapHttpsFetch(pin, open, trust).get(url)
    if (result.status != 200) throw JmapFailure("jmap push status ${result.status}")
    return parseJmapPushEvents(result.body)
}

fun jmapApplyPush(
    session: JmapSession,
    sinceState: String,
    username: String,
    password: String,
    pin: String,
    open: (String) -> JmapHttpExchange,
    post: (String, String, String) -> JmapHttpExchange,
    trust: (String, List<ByteArray>, String) -> String = PeerTrust::check,
): JmapEmailChanges? {
    val accountId = session.primaryMailAccountId
    if (accountId.isNullOrBlank()) throw JmapFailure("jmap account id is empty")
    if (sinceState.isBlank()) throw JmapFailure("jmap changes state is empty")
    jmapBasicAuthorization(username, password)
    val events = jmapReadPush(session.eventSourceUrl, pin, open, trust)
    var found: String? = null
    for (event in events) {
        val email = jmapPushEmailState(event.data, accountId) ?: continue
        found = email
        break
    }
    if (found == null || found == sinceState) return null
    return jmapEmailChanges(session, sinceState, username, password, pin, post, trust)
}

fun platformJmapEventSource(url: String, authorization: String, pin: String): JmapHttpExchange {
    val connection = (URI(url).toURL().openConnection() as HttpsURLConnection).apply {
        pinJmapTls(this, pin)
        instanceFollowRedirects = false
        connectTimeout = 15_000
        readTimeout = 150_000 // F82: above the ping=120 the request asks for
        requestMethod = "GET"
        setRequestProperty("Accept", "text/event-stream")
        setRequestProperty("Authorization", authorization)
    }
    return object : JmapHttpExchange {
        override val status: Int
            get() = connection.responseCode

        override fun peerDer(): List<ByteArray> =
            connection.serverCertificates.map { it.encoded }

        override fun header(name: String): String? = connection.getHeaderField(name)

        override fun body(): String {
            val stream = if (connection.responseCode < 400) {
                connection.inputStream
            } else {
                connection.errorStream
            }
            if (stream == null) return ""
            return stream.use { readJmapBody(it) }
        }

        override fun close() {
            connection.disconnect()
        }
    }
}

private fun eventSourceIsHttps(url: String): Boolean {
    val scheme = try {
        URI(url).scheme
    } catch (_: Exception) {
        null
    }
    return scheme != null && scheme.equals("https", ignoreCase = true)
}
