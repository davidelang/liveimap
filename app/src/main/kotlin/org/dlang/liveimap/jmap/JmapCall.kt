package org.dlang.liveimap.jmap

import org.dlang.liveimap.engine.PeerTrust
import org.dlang.liveimap.engine.TlsPolicy
import org.dlang.liveimap.engine.applyTlsPolicy
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
    policy: TlsPolicy = TlsPolicy(),
    accountKey: String = "",
): JmapHttpResult {
    if (apiUrl.isBlank()) throw JmapFailure("jmap api url is empty")
    if (!jmapUrlAllowed(accountKey, apiUrl)) throw JmapFailure("jmap api url is not https")
    val host = apiHost(apiUrl)
    val exchange = jmapNet { post(apiUrl, body, authorization) }
    try {
        val verdict = applyTlsPolicy(jmapVerdict(accountKey, apiUrl) { jmapNet { trust(host, exchange.peerDer(), pin) } }, policy)
        if (verdict.isNotEmpty()) throw JmapFailure(verdict)
        val status = jmapNet { exchange.status }
        if (status in apiRedirectStatuses) throw JmapFailure("jmap api redirect")
        return JmapHttpResult(status, jmapNet { exchange.body() }, apiUrl)
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
    policy: TlsPolicy = TlsPolicy(),
    accountKey: String = "",
): List<JmapMailbox> {
    val accountId = session.primaryMailAccountId
    if (accountId.isNullOrBlank()) throw JmapFailure("jmap account id is empty")
    val request = jmapMailboxLevelRequest(accountId, parentId)
    val authorization = jmapBasicAuthorization(username, password)
    val result = jmapCall(session.apiUrl, request, authorization, pin, post, trust, policy, accountKey)
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
    policy: TlsPolicy = TlsPolicy(),
    accountKey: String = "",
): Set<String> {
    if (mailboxIds.isEmpty()) throw JmapFailure("jmap mailbox ids are empty")
    val accountId = session.primaryMailAccountId
    if (accountId.isNullOrBlank()) throw JmapFailure("jmap account id is empty")
    val request = jmapMailboxChildProbe(accountId, mailboxIds)
    val authorization = jmapBasicAuthorization(username, password)
    val result = jmapCall(session.apiUrl, request, authorization, pin, post, trust, policy, accountKey)
    if (result.status != 200) throw JmapFailure("jmap api status ${result.status}")
    val root = try {
        JsonParser(result.body).parseDocument()
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
        return parentIdsOf(list)
    }
    throw JmapFailure("jmap mailbox response lacks get")
}

fun platformJmapPost(url: String, body: String, authorization: String, pin: String, policy: TlsPolicy = TlsPolicy()): JmapHttpExchange {
    val connection = (URI(url).toURL().openConnection() as java.net.HttpURLConnection).apply {
        if (this is HttpsURLConnection) pinJmapTls(this, pin, policy)
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
            connection.connect().let { (connection as? HttpsURLConnection)?.serverCertificates?.map { it.encoded } ?: emptyList() } // certificates exist only once connected (no-op if already)

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

fun platformJmapUpload(
    url: String,
    bytes: ByteArray,
    contentType: String,
    authorization: String,
    pin: String,
    policy: TlsPolicy = TlsPolicy(),
): JmapHttpExchange {
    val connection = (URI(url).toURL().openConnection() as java.net.HttpURLConnection).apply {
        if (this is HttpsURLConnection) pinJmapTls(this, pin, policy)
        instanceFollowRedirects = false
        connectTimeout = 15_000
        readTimeout = 15_000
        requestMethod = "POST"
        doOutput = true
        setRequestProperty("Accept", "application/json")
        setRequestProperty("Content-Type", contentType)
        setRequestProperty("Authorization", authorization)
    }
    connection.outputStream.use { stream ->
        stream.write(bytes)
    }
    return object : JmapHttpExchange {
        override val status: Int
            get() = connection.responseCode

        override fun peerDer(): List<ByteArray> =
            connection.connect().let { (connection as? HttpsURLConnection)?.serverCertificates?.map { it.encoded } ?: emptyList() } // certificates exist only once connected (no-op if already)

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

private val apiRedirectStatuses = setOf(301, 302, 303, 307, 308)

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
