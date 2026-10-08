package org.dlang.liveimap.jmap

import org.dlang.liveimap.engine.PeerTrust
import java.net.URI
import java.util.Base64
import javax.net.ssl.HttpsURLConnection

fun jmapBasicAuthorization(username: String, password: String): String {
    if (username.isBlank()) throw JmapFailure("jmap username is empty")
    val token = Base64.getEncoder().encodeToString(
        "$username:$password".toByteArray(Charsets.UTF_8),
    )
    return "Basic $token"
}

fun jmapCall(
    apiUrl: String,
    body: String,
    authorization: String,
    pin: String,
    post: (String, String, String) -> JmapHttpExchange,
    trust: (String, List<ByteArray>, String) -> String = PeerTrust::check,
): JmapHttpResult {
    if (apiUrl.isBlank()) throw JmapFailure("jmap api url is empty")
    if (!httpsApi(apiUrl)) throw JmapFailure("jmap api url is not https")
    val host = apiHost(apiUrl)
    val exchange = post(apiUrl, body, authorization)
    try {
        val verdict = trust(host, exchange.peerDer(), pin)
        if (verdict.isNotEmpty()) throw JmapFailure(verdict)
        val status = exchange.status
        if (status in apiRedirectStatuses) throw JmapFailure("jmap api redirect")
        return JmapHttpResult(status, exchange.body(), apiUrl)
    } finally {
        exchange.close()
    }
}

fun jmapMailboxLevel(
    session: JmapSession,
    parentId: String?,
    username: String,
    password: String,
    pin: String,
    post: (String, String, String) -> JmapHttpExchange,
    trust: (String, List<ByteArray>, String) -> String = PeerTrust::check,
): List<JmapMailbox> {
    val accountId = session.primaryMailAccountId
    if (accountId.isNullOrBlank()) throw JmapFailure("jmap account id is empty")
    val request = jmapMailboxLevelRequest(accountId, parentId)
    val authorization = jmapBasicAuthorization(username, password)
    val result = jmapCall(session.apiUrl, request, authorization, pin, post, trust)
    if (result.status != 200) throw JmapFailure("jmap api status ${result.status}")
    return parseJmapMailboxes(result.body)
}

fun jmapMailboxChildren(
    session: JmapSession,
    mailboxIds: List<String>,
    username: String,
    password: String,
    pin: String,
    post: (String, String, String) -> JmapHttpExchange,
    trust: (String, List<ByteArray>, String) -> String = PeerTrust::check,
): Set<String> {
    if (mailboxIds.isEmpty()) throw JmapFailure("jmap mailbox ids are empty")
    val accountId = session.primaryMailAccountId
    if (accountId.isNullOrBlank()) throw JmapFailure("jmap account id is empty")
    val request = jmapMailboxChildProbe(accountId, mailboxIds)
    val authorization = jmapBasicAuthorization(username, password)
    val result = jmapCall(session.apiUrl, request, authorization, pin, post, trust)
    if (result.status != 200) throw JmapFailure("jmap api status ${result.status}")
    return jmapMailboxParentIds(parentListText(result.body))
}

fun platformJmapPost(url: String, body: String, authorization: String): JmapHttpExchange {
    val connection = (URI(url).toURL().openConnection() as HttpsURLConnection).apply {
        instanceFollowRedirects = false
        connectTimeout = 15_000
        readTimeout = 15_000
        requestMethod = "POST"
        doOutput = true
        setRequestProperty("Accept", "application/json")
        setRequestProperty("Content-Type", "application/json")
        setRequestProperty("Authorization", authorization)
    }
    connection.outputStream.use { stream ->
        stream.write(body.toByteArray(Charsets.UTF_8))
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
            return stream.use { it.readBytes().toString(Charsets.UTF_8) }
        }

        override fun close() {
            connection.disconnect()
        }
    }
}

private val apiRedirectStatuses = setOf(301, 302, 303, 307, 308)

private fun httpsApi(url: String): Boolean {
    val scheme = try {
        URI(url).scheme
    } catch (_: Exception) {
        null
    }
    return scheme != null && scheme.equals("https", ignoreCase = true)
}

private fun apiHost(url: String): String {
    val host = try {
        URI(url).host
    } catch (_: Exception) {
        null
    } ?: throw JmapFailure("jmap api url is not https")
    if (host.startsWith("[") && host.endsWith("]") && host.length >= 2) {
        return host.substring(1, host.length - 1)
    }
    return host
}

// jmapMailboxParentIds reads a JSON array. The parser does not keep source spans.
private fun parentListText(text: String): String {
    val root = try {
        JsonParser(text).parseDocument()
    } catch (_: JsonBroken) {
        throw JmapFailure("jmap mailbox response is not an object")
    }
    if (root !is Json.Obj) throw JmapFailure("jmap mailbox response is not an object")
    val responses = root.fields["methodResponses"]
    if (responses !is Json.Arr) throw JmapFailure("jmap mailbox response lacks get")
    for (item in responses.values) {
        if (item !is Json.Arr || item.values.size < 2) continue
        val name = item.values[0]
        if (name !is Json.Str || name.text != "Mailbox/get") continue
        val args = item.values[1]
        if (args !is Json.Obj) throw JmapFailure("jmap mailbox response lacks list")
        val list = args.fields["list"] ?: throw JmapFailure("jmap mailbox response lacks list")
        if (list !is Json.Arr) throw JmapFailure("jmap mailbox list is not an array")
        return renderParentList(list)
    }
    throw JmapFailure("jmap mailbox response lacks get")
}

private fun renderParentList(list: Json.Arr): String {
    val parts = list.values.map { item ->
        if (item !is Json.Obj) return@map "1"
        if (!item.fields.containsKey("parentId")) return@map "{}"
        when (val parent = item.fields["parentId"]) {
            Json.Null -> """{"parentId":null}"""
            is Json.Str -> """{"parentId":${jsonQuote(parent.text)}}"""
            else -> """{"parentId":1}"""
        }
    }
    return parts.joinToString(prefix = "[", postfix = "]", separator = ",")
}

private fun jsonQuote(text: String): String {
    val out = StringBuilder(text.length + 2)
    out.append('"')
    for (c in text) {
        when (c) {
            '"' -> out.append("\\\"")
            '\\' -> out.append("\\\\")
            '\b' -> out.append("\\b")
            '\u000C' -> out.append("\\f")
            '\n' -> out.append("\\n")
            '\r' -> out.append("\\r")
            '\t' -> out.append("\\t")
            else -> if (c.code < 0x20) {
                out.append("\\u")
                out.append(c.code.toString(16).padStart(4, '0'))
            } else {
                out.append(c)
            }
        }
    }
    out.append('"')
    return out.toString()
}
