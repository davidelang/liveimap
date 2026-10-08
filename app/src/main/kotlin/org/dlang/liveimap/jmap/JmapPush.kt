package org.dlang.liveimap.jmap

import org.dlang.liveimap.engine.PeerTrust
import java.net.URI

data class JmapPushEvent(
    val name: String,
    val data: String,
)

fun jmapEventSourceUrl(eventSourceUrl: String): String {
    if (eventSourceUrl.isBlank()) throw JmapFailure("jmap event source url is empty")
    if (eventSourceUrl.contains('#')) throw JmapFailure("jmap event source url has a fragment")
    if (!eventSourceIsHttps(eventSourceUrl)) {
        throw JmapFailure("jmap event source url is not https")
    }
    val joiner = if (eventSourceUrl.contains('?')) "&" else "?"
    return eventSourceUrl + joiner + "types=Email&closeafter=state&ping=120"
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

private fun eventSourceIsHttps(url: String): Boolean {
    val scheme = try {
        URI(url).scheme
    } catch (_: Exception) {
        null
    }
    return scheme != null && scheme.equals("https", ignoreCase = true)
}
